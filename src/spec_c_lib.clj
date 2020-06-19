;; 1. take a c prototype
;; 2. generate data as below
;; 3. generate c shadowing function
;; 4. generate clojure polyglot code
;; 5. generate clojure ni code

(ns spec-c-lib
  (:require [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            
            [gen-c :as gcee :refer [get-c-path get-h-path]]
            [gen-clj :as gclj]
            [gen-clj.polyglot :as poly]
            
            [clojure.pprint :refer [pp pprint]]))

(defn shadow-data
  [f]
  (update f :sym #(str "_SHADOWING_" %)))

(defn gen-both
  [{:keys [lib-name includes protos inline-c] :as opts}]
  (let [c-file (gcee/gen-c-file [(str (last (str/split (gcee/snake-case lib-name) #"/")) ".h")]
                                (map gcee/generate-shadowing-function protos)
                                opts)
        
        protos-as-data-shadowed (map shadow-data protos)
        
        h-file (gcee/gen-h-file includes
                                (map gcee/generate-c-prototype protos-as-data-shadowed))
        
        clojure-mappings (concat
                          (map #(gclj/gen-clojure-mapping % 
                                                          {:prefixes ["_SHADOWING_SDL"
                                                                      "_SHADOWING_"]})
                               protos-as-data-shadowed))
        
        clojure-lib (poly/gen-lib lib-name clojure-mappings
                                  opts)]
    (merge opts
           {:lib-name lib-name
            :c-code c-file
            :h-code h-file
            :clojure-mappings clojure-mappings
            :clojure-lib clojure-lib})))

(defn persist-clj-poly
  [{:keys [src-dir lib-name] :as res}]
  (let [path (str src-dir
                  "/"
                  (str/replace (str lib-name) "." "/") ".clj")
        f (doseq [d (->> (reduce
                          (fn [acc curr]
                            (conj acc (str (last acc) "/" curr)))
                          []
                          (butlast (str/split path #"/")))
                         (map #(io/file (str (System/getProperty "user.dir") "/" %))))]
            (println "Creating dir" d)
            (.mkdir d))]
    (println "Persisting clj to:" path)
    (with-open [wrtr (io/writer path)]
      (.write wrtr ";; This file is autogenerated -- probably shouldn't modify it by hand\n")
      (.write wrtr
              (with-out-str (doseq [f (:clojure-lib res)]
                              (pprint f)
                              (print "\n")))))))

(defn gen-and-persist
  "Takes a lib name as symbol and options required to generate c and clojure code. "
  [opts]
  (let [{:keys [skip-c-gen] :as res} (gen-both opts)]
    (if skip-c-gen
      (println ":skip-c-gen true, no c-code generated.")
      (gcee/persist-c res))
    (persist-clj-poly res)
    res))

(comment
  
  (pprint (parse-c-prototype "int SDL_Init(Uint32 flags, a * b, const c d)"))
  
  (pprint (parse-c-prototype "void SDL_Quit()"))
  (pprint (parse-c-prototype "int* SDL_Init(Uint32 flags, a * b, c d)"))
  
  (-> (parse-c-prototype "int* SDL_Init(Uint32 flags, a * b, c d)")
      generate-shadowing-function
      println)
  
  (-> (parse-c-prototype "int* SDL_Init(Uint32 flags, a * b, c d)")
      (gen-clojure-mapping {:prefixes ["SDL_"]})
      println)
  
  
  
  {'SDLEvent {:type "SDL_Event"}
   
   ;; int SDL_Init(Uint32 flags)
   'init {:ret 'int
          :sym "SDL_Init"
          :args [{:type "Uint32"
                  :sym "flags"}]}
   
   ;; int SDL_PollEvent(SDL_Event* event)
   'poll-event {:ret 'int
                :sym "SDL_PollEvent"
                :args [{:type "SDL_Event"
                        :pointer "*"
                        :sym "event"}]}})
