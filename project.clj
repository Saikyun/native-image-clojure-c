(defproject woop "0.0.1"
  :dependencies [[org.clojure/clojure "1.10.1"]]
  :source-paths ["src"]
  ;;:java-source-paths ["src"]
  :resource-paths [#_"extra-classes" #_"classes"]
  :jvm-opts ["-Dclojure.compiler.direct-linking=true"
             "-Dclojure.spec.skip-macros=true"
             "-Dclojure.server.repl={:port 5555 :accept clojure.core.server/repl}"
             ]
  :aot :all
  :profiles {:clojure-1.10.2-alpha1 {:dependencies [[org.clojure/clojure "1.10.2-alpha1"]]}
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :main core
                       :aot :all}})
