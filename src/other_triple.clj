(in-ns 'clojure.core)

(defn- generate-class2 [options-map]
  (validate-generate-class-options options-map)
  (let [default-options {:prefix "-" :load-impl-ns true :impl-ns (ns-name *ns*)}
        {:keys [name extends implements constructors methods main factory state init exposes 
                exposes-methods prefix load-impl-ns impl-ns post-init]} 
        (merge default-options options-map)
        name-meta (meta name)
        name (str name)
        super (if extends (the-class extends) Object)
        interfaces (map the-class implements)
        supers (cons super interfaces)
        ctor-sig-map (or constructors (zipmap (ctor-sigs super) (ctor-sigs super)))
        cv (clojure.lang.Compiler/classWriter)
        cname (. name (replace "." "/"))
        pkg-name name
        impl-pkg-name (str impl-ns)
        impl-cname (.. impl-pkg-name (replace "." "/") (replace \- \_))
        ctype (. Type (getObjectType cname))
        iname (fn [^Class c] (.. Type (getType c) (getInternalName)))
        totype (fn [^Class c] (. Type (getType c)))
        to-types (fn [cs] (if (pos? (count cs))
                            (into-array (map totype cs))
                            (make-array Type 0)))
        obj-type ^Type (totype Object)
        arg-types (fn [n] (if (pos? n)
                            (into-array (replicate n obj-type))
                            (make-array Type 0)))
        super-type ^Type (totype super)
        init-name (str init)
        post-init-name (str post-init)
        factory-name (str factory)
        state-name (str state)
        main-name "main"
        var-name (fn [s] (clojure.lang.Compiler/munge (str s "__var")))
        class-type  (totype Class)
        rt-type  (totype clojure.lang.RT)
        var-type ^Type (totype clojure.lang.Var)
        ifn-type (totype clojure.lang.IFn)
        iseq-type (totype clojure.lang.ISeq)
        ex-type  (totype java.lang.UnsupportedOperationException)
        util-type (totype clojure.lang.Util)
        all-sigs (distinct (concat (map #(let[[m p] (key %)] {m [p]}) (mapcat non-private-methods supers))
                                   (map (fn [[m p]] {(str m) [p]}) methods)))
        sigs-by-name (apply merge-with concat {} all-sigs)
        overloads (into1 {} (filter (fn [[m s]] (next s)) sigs-by-name))
        var-fields (concat (when init [init-name]) 
                           (when post-init [post-init-name])
                           (when main [main-name])
                                        ;(when exposes-methods (map str (vals exposes-methods)))
                           (distinct (concat (keys sigs-by-name)
                                             (mapcat (fn [[m s]] (map #(overload-name m (map the-class %)) s)) overloads)
                                             (mapcat (comp (partial map str) vals val) exposes))))
        emit-get-var (fn [^GeneratorAdapter gen v]
                       (let [false-label (. gen newLabel)
                             end-label (. gen newLabel)]
                         (. gen getStatic ctype (var-name v) var-type)
                         (. gen dup)
                         (. gen invokeVirtual var-type (. Method (getMethod "boolean isBound()")))
                         (. gen ifZCmp (. GeneratorAdapter EQ) false-label)
                         (. gen invokeVirtual var-type (. Method (getMethod "Object get()")))
                         (. gen goTo end-label)
                         (. gen mark false-label)
                         (. gen pop)
                         (. gen visitInsn (. Opcodes ACONST_NULL))
                         (. gen mark end-label)))
        emit-unsupported (fn [^GeneratorAdapter gen ^Method m]
                           (. gen (throwException ex-type (str (. m (getName)) " ("
                                                               impl-pkg-name "/" prefix (.getName m)
                                                               " not defined?)"))))
        emit-forwarding-method
        (fn [name pclasses rclass as-static as-native else-gen]
          (let [mname (str name)
                pmetas (map meta pclasses)
                pclasses (map the-class pclasses)
                rclass (the-class rclass)
                ptypes (to-types pclasses)
                rtype ^Type (totype rclass)
                m (new Method mname rtype ptypes)
                is-overload (seq (overloads mname))
                gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC)
                                             (if as-static (. Opcodes ACC_STATIC) 0)
                                             (if as-native (. Opcodes ACC_NATIVE) 0))
                         m nil nil cv)
                found-label (. gen (newLabel))
                else-label (. gen (newLabel))
                end-label (. gen (newLabel))]
            (add-annotations gen (meta name))
            (dotimes [i (count pmetas)]
              (add-annotations gen (nth pmetas i) i))
            (when-not as-native
              (. gen (visitCode))
              (if (> (count pclasses) 18)
                (else-gen gen m)
                (do
                  (when is-overload
                    (emit-get-var gen (overload-name mname pclasses))
                    (. gen (dup))
                    (. gen (ifNonNull found-label))
                    (. gen (pop)))
                  (emit-get-var gen mname)
                  (. gen (dup))
                  (. gen (ifNull else-label))
                  (when is-overload
                    (. gen (mark found-label)))
                                        ;if found
                  (.checkCast gen ifn-type)
                  (when-not as-static
                    (. gen (loadThis)))
                                        ;box args
                  (dotimes [i (count ptypes)]
                    (. gen (loadArg i))
                    (. clojure.lang.Compiler$HostExpr (emitBoxReturn nil gen (nth pclasses i))))
                                        ;call fn
                  (. gen (invokeInterface ifn-type (new Method "invoke" obj-type
                                                        (to-types (replicate (+ (count ptypes)
                                                                                (if as-static 0 1))
                                                                             Object)))))
                                        ;(into-array (cons obj-type
                                        ;                 (replicate (count ptypes) obj-type))))))
                                        ;unbox return
                  (. gen (unbox rtype))
                  (when (= (. rtype (getSort)) (. Type VOID))
                    (. gen (pop)))
                  (. gen (goTo end-label))

                                        ;else call supplied alternative generator
                  (. gen (mark else-label))
                  (. gen (pop))

                  (else-gen gen m)

                  (. gen (mark end-label))))
              (. gen (returnValue)))
            (. gen (endMethod))))
        ]
                                        ;start class definition
    (. cv (visit (. Opcodes V1_8) (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_SUPER))
                 cname nil (iname super)
                 (when-let [ifc (seq interfaces)]
                   (into-array (map iname ifc)))))

                                        ; class annotations
    (add-annotations cv name-meta)
    
                                        ;static fields for vars
    (doseq [v var-fields]
      (. cv (visitField (+ (. Opcodes ACC_PRIVATE) (. Opcodes ACC_FINAL) (. Opcodes ACC_STATIC))
                        (var-name v) 
                        (. var-type getDescriptor)
                        nil nil)))
    
                                        ;instance field for state
    (when state
      (. cv (visitField (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_FINAL))
                        state-name 
                        (. obj-type getDescriptor)
                        nil nil)))
    
                                        ;static init to set up var fields and load init
    (let [gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                   (. Method getMethod "void <clinit> ()")
                   nil nil cv)]
      (. gen (visitCode))
      (doseq [v var-fields]
        (. gen push impl-pkg-name)
        (. gen push (str prefix v))
        (. gen (invokeStatic var-type (. Method (getMethod "clojure.lang.Var internPrivate(String,String)"))))
        (. gen putStatic ctype (var-name v) var-type))
      
      (when load-impl-ns
        (. gen push (str "/" impl-cname))
        (. gen push ctype)
        (. gen (invokeStatic util-type (. Method (getMethod "Object loadWithClass(String,Class)"))))
                                        ;        (. gen push (str (.replace impl-pkg-name \- \_) "__init"))
                                        ;        (. gen (invokeStatic class-type (. Method (getMethod "Class forName(String)"))))
        (. gen pop))

      (. gen (returnValue))
      (. gen (endMethod)))
    
                                        ;ctors
    (doseq [[pclasses super-pclasses] ctor-sig-map]
      (let [constructor-annotations (meta pclasses)
            pclasses (map the-class pclasses)
            super-pclasses (map the-class super-pclasses)
            ptypes (to-types pclasses)
            super-ptypes (to-types super-pclasses)
            m (new Method "<init>" (. Type VOID_TYPE) ptypes)
            super-m (new Method "<init>" (. Type VOID_TYPE) super-ptypes)
            gen (new GeneratorAdapter (. Opcodes ACC_PUBLIC) m nil nil cv)
            _ (add-annotations gen constructor-annotations)
            no-init-label (. gen newLabel)
            end-label (. gen newLabel)
            no-post-init-label (. gen newLabel)
            end-post-init-label (. gen newLabel)
            nth-method (. Method (getMethod "Object nth(Object,int)"))
            local (. gen newLocal obj-type)]
        (. gen (visitCode))
        
        (if init
          (do
            (emit-get-var gen init-name)
            (. gen dup)
            (. gen ifNull no-init-label)
            (.checkCast gen ifn-type)
                                        ;box init args
            (dotimes [i (count pclasses)]
              (. gen (loadArg i))
              (. clojure.lang.Compiler$HostExpr (emitBoxReturn nil gen (nth pclasses i))))
                                        ;call init fn
            (. gen (invokeInterface ifn-type (new Method "invoke" obj-type 
                                                  (arg-types (count ptypes)))))
                                        ;expecting [[super-ctor-args] state] returned
            (. gen dup)
            (. gen push (int 0))
            (. gen (invokeStatic rt-type nth-method))
            (. gen storeLocal local)
            
            (. gen (loadThis))
            (. gen dupX1)
            (dotimes [i (count super-pclasses)]
              (. gen loadLocal local)
              (. gen push (int i))
              (. gen (invokeStatic rt-type nth-method))
              (. clojure.lang.Compiler$HostExpr (emitUnboxArg nil gen (nth super-pclasses i))))
            (. gen (invokeConstructor super-type super-m))
            
            (if state
              (do
                (. gen push (int 1))
                (. gen (invokeStatic rt-type nth-method))
                (. gen (putField ctype state-name obj-type)))
              (. gen pop))
            
            (. gen goTo end-label)
                                        ;no init found
            (. gen mark no-init-label)
            (. gen (throwException ex-type (str impl-pkg-name "/" prefix init-name " not defined")))
            (. gen mark end-label))
          (if (= pclasses super-pclasses)
            (do
              (. gen (loadThis))
              (. gen (loadArgs))
              (. gen (invokeConstructor super-type super-m)))
            (throw (new Exception ":init not specified, but ctor and super ctor args differ"))))

        (when post-init
          (emit-get-var gen post-init-name)
          (. gen dup)
          (. gen ifNull no-post-init-label)
          (.checkCast gen ifn-type)
          (. gen (loadThis))
                                        ;box init args
          (dotimes [i (count pclasses)]
            (. gen (loadArg i))
            (. clojure.lang.Compiler$HostExpr (emitBoxReturn nil gen (nth pclasses i))))
                                        ;call init fn
          (. gen (invokeInterface ifn-type (new Method "invoke" obj-type 
                                                (arg-types (inc (count ptypes))))))
          (. gen pop)
          (. gen goTo end-post-init-label)
                                        ;no init found
          (. gen mark no-post-init-label)
          (. gen (throwException ex-type (str impl-pkg-name "/" prefix post-init-name " not defined")))
          (. gen mark end-post-init-label))

        (. gen (returnValue))
        (. gen (endMethod))
                                        ;factory
        (when factory
          (let [fm (new Method factory-name ctype ptypes)
                gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                         fm nil nil cv)]
            (. gen (visitCode))
            (. gen newInstance ctype)
            (. gen dup)
            (. gen (loadArgs))
            (. gen (invokeConstructor ctype m))            
            (. gen (returnValue))
            (. gen (endMethod))))))
    
                                        ;add methods matching supers', if no fn -> call super
    (let [mm (non-private-methods super)]
      (doseq [^java.lang.reflect.Method meth (vals mm)]
        (emit-forwarding-method (.getName meth) (.getParameterTypes meth) (.getReturnType meth) false false
                                (fn [^GeneratorAdapter gen ^Method m]
                                  (. gen (loadThis))
                                        ;push args
                                  (. gen (loadArgs))
                                        ;call super
                                  (. gen (visitMethodInsn (. Opcodes INVOKESPECIAL) 
                                                          (. super-type (getInternalName))
                                                          (. m (getName))
                                                          (. m (getDescriptor)))))))
                                        ;add methods matching interfaces', if no fn -> throw
      (reduce1 (fn [mm ^java.lang.reflect.Method meth]
                 (if (contains? mm (method-sig meth))
                   mm
                   (do
                     (emit-forwarding-method (.getName meth) (.getParameterTypes meth) (.getReturnType meth) false false
                                             emit-unsupported)
                     (assoc mm (method-sig meth) meth))))
               mm (mapcat #(.getMethods ^Class %) interfaces))
                                        ;extra methods
      (doseq [[mname pclasses rclass :as msig] methods]
        (emit-forwarding-method mname pclasses rclass (:static (meta msig)) (:native (meta msig))
                                emit-unsupported))
                                        ;expose specified overridden superclass methods
      (doseq [[local-mname ^java.lang.reflect.Method m] (reduce1 (fn [ms [[name _ _] m]]
                                                                   (if (contains? exposes-methods (symbol name))
                                                                     (conj ms [((symbol name) exposes-methods) m])
                                                                     ms)) [] (concat (seq mm)
                                                                                     (seq (protected-final-methods super))))]
        (let [ptypes (to-types (.getParameterTypes m))
              rtype (totype (.getReturnType m))
              exposer-m (new Method (str local-mname) rtype ptypes)
              target-m (new Method (.getName m) rtype ptypes)
              gen (new GeneratorAdapter (. Opcodes ACC_PUBLIC) exposer-m nil nil cv)]
          (. gen (loadThis))
          (. gen (loadArgs))
          (. gen (visitMethodInsn (. Opcodes INVOKESPECIAL) 
                                  (. super-type (getInternalName))
                                  (. target-m (getName))
                                  (. target-m (getDescriptor))))
          (. gen (returnValue))
          (. gen (endMethod)))))
                                        ;main
    (when main
      (let [m (. Method getMethod "void main (String[])")
            gen (new GeneratorAdapter (+ (. Opcodes ACC_PUBLIC) (. Opcodes ACC_STATIC)) 
                     m nil nil cv)
            no-main-label (. gen newLabel)
            end-label (. gen newLabel)]
        (. gen (visitCode))

        (emit-get-var gen main-name)
        (. gen dup)
        (. gen ifNull no-main-label)
        (.checkCast gen ifn-type)
        (. gen loadArgs)
        (. gen (invokeStatic rt-type (. Method (getMethod "clojure.lang.ISeq seq(Object)"))))
        (. gen (invokeInterface ifn-type (new Method "applyTo" obj-type 
                                              (into-array [iseq-type]))))
        (. gen pop)
        (. gen goTo end-label)
                                        ;no main found
        (. gen mark no-main-label)
        (. gen (throwException ex-type (str impl-pkg-name "/" prefix main-name " not defined")))
        (. gen mark end-label)
        (. gen (returnValue))
        (. gen (endMethod))))
                                        ;field exposers
    (doseq [[f {getter :get setter :set}] exposes]
      (let [fld (find-field super (str f))
            ftype (totype (.getType fld))
            static? (Modifier/isStatic (.getModifiers fld))
            acc (+ Opcodes/ACC_PUBLIC (if static? Opcodes/ACC_STATIC 0))]
        (when getter
          (let [m (new Method (str getter) ftype (to-types []))
                gen (new GeneratorAdapter acc m nil nil cv)]
            (. gen (visitCode))
            (if static?
              (. gen getStatic ctype (str f) ftype)
              (do
                (. gen loadThis)
                (. gen getField ctype (str f) ftype)))
            (. gen (returnValue))
            (. gen (endMethod))))
        (when setter
          (let [m (new Method (str setter) Type/VOID_TYPE (into-array [ftype]))
                gen (new GeneratorAdapter acc m nil nil cv)]
            (. gen (visitCode))
            (if static?
              (do
                (. gen loadArgs)
                (. gen putStatic ctype (str f) ftype))
              (do
                (. gen loadThis)
                (. gen loadArgs)
                (. gen putField ctype (str f) ftype)))
            (. gen (returnValue))
            (. gen (endMethod))))))
                                        ;finish class def
    (. cv (visitEnd))
    [cname (. cv (toByteArray))]))

(defmacro gen-class2
  "When compiling, generates compiled bytecode for a class with the
  given package-qualified :name (which, as all names in these
  parameters, can be a string or symbol), and writes the .class file
  to the *compile-path* directory.  When not compiling, does
  nothing. The gen-class construct contains no implementation, as the
  implementation will be dynamically sought by the generated class in
  functions in an implementing Clojure namespace. Given a generated
  class org.mydomain.MyClass with a method named mymethod, gen-class
  will generate an implementation that looks for a function named by 
  (str prefix mymethod) (default prefix: \"-\") in a
  Clojure namespace specified by :impl-ns
  (defaults to the current namespace). All inherited methods,
  generated methods, and init and main functions (see :methods, :init,
  and :main below) will be found similarly prefixed. By default, the
  static initializer for the generated class will attempt to load the
  Clojure support code for the class as a resource from the classpath,
  e.g. in the example case, ``org/mydomain/MyClass__init.class``. This
  behavior can be controlled by :load-impl-ns

  Note that methods with a maximum of 18 parameters are supported.

  In all subsequent sections taking types, the primitive types can be
  referred to by their Java names (int, float etc), and classes in the
  java.lang package can be used without a package qualifier. All other
  classes must be fully qualified.

  Options should be a set of key/value pairs, all except for :name are optional:

  :name aname

  The package-qualified name of the class to be generated

  :extends aclass

  Specifies the superclass, the non-private methods of which will be
  overridden by the class. If not provided, defaults to Object.

  :implements [interface ...]

  One or more interfaces, the methods of which will be implemented by the class.

  :init name

  If supplied, names a function that will be called with the arguments
  to the constructor. Must return [ [superclass-constructor-args] state] 
  If not supplied, the constructor args are passed directly to
  the superclass constructor and the state will be nil

  :constructors {[param-types] [super-param-types], ...}

  By default, constructors are created for the generated class which
  match the signature(s) of the constructors for the superclass. This
  parameter may be used to explicitly specify constructors, each entry
  providing a mapping from a constructor signature to a superclass
  constructor signature. When you supply this, you must supply an :init
  specifier. 

  :post-init name

  If supplied, names a function that will be called with the object as
  the first argument, followed by the arguments to the constructor.
  It will be called every time an object of this class is created,
  immediately after all the inherited constructors have completed.
  Its return value is ignored.

  :methods [ [name [param-types] return-type], ...]

  The generated class automatically defines all of the non-private
  methods of its superclasses/interfaces. This parameter can be used
  to specify the signatures of additional methods of the generated
  class. Static methods can be specified with ^{:static true} in the
  signature's metadata. Do not repeat superclass/interface signatures
  here.

  :main boolean

  If supplied and true, a static public main function will be generated. It will
  pass each string of the String[] argument as a separate argument to
  a function called (str prefix main).

  :factory name

  If supplied, a (set of) public static factory function(s) will be
  created with the given name, and the same signature(s) as the
  constructor(s).
  
  :state name

  If supplied, a public final instance field with the given name will be
  created. You must supply an :init function in order to provide a
  value for the state. Note that, though final, the state can be a ref
  or agent, supporting the creation of Java objects with transactional
  or asynchronous mutation semantics.

  :exposes {protected-field-name {:get name :set name}, ...}

  Since the implementations of the methods of the generated class
  occur in Clojure functions, they have no access to the inherited
  protected fields of the superclass. This parameter can be used to
  generate public getter/setter methods exposing the protected field(s)
  for use in the implementation.

  :exposes-methods {super-method-name exposed-name, ...}

  It is sometimes necessary to call the superclass' implementation of an
  overridden method.  Those methods may be exposed and referred in 
  the new method implementation by a local name.

  :prefix string

  Default: \"-\" Methods called e.g. Foo will be looked up in vars called
  prefixFoo in the implementing ns.

  :impl-ns name

  Default: the name of the current ns. Implementations of methods will be 
  looked up in this namespace.

  :load-impl-ns boolean

  Default: true. Causes the static initializer for the generated class
  to reference the load code for the implementing namespace. Should be
  true when implementing-ns is the default, false if you intend to
  load the code via some other method."
  {:added "1.0"}
  
  [& options]
  (when *compile-files*
    (let [options-map (into1 {} (map vec (partition 2 options)))
          [cname bytecode] (generate-class2 options-map)]
      (clojure.lang.Compiler/writeClassFile cname bytecode))))

(ns other-triple
  (:import org.graalvm.word.PointerBase
           org.graalvm.nativeimage.c.struct.CField
           org.graalvm.nativeimage.c.CContext
           org.graalvm.nativeimage.c.function.CFunction
           org.graalvm.nativeimage.c.function.CLibrary
           org.graalvm.nativeimage.c.struct.CFieldAddress
           org.graalvm.nativeimage.c.struct.CStruct
           org.graalvm.nativeimage.c.struct.AllowWideningCast
           org.graalvm.nativeimage.c.function.CFunction)
  (:gen-class))

(deftype Headers
    []
  org.graalvm.nativeimage.c.CContext$Directives
  (getHeaderFiles
    [this]
    ["\"/Users/test/programmering/clojure/graal-native-interaction/graal/src/triple.h\""]))

(gen-interface
 :name ^{org.graalvm.nativeimage.c.CContext other_triple.Headers
         org.graalvm.nativeimage.c.function.CLibrary "triple"
         org.graalvm.nativeimage.c.struct.CStruct "value_t"}
 wat.cool.OOValue
 :extends [org.graalvm.word.PointerBase]
 :methods [[^{org.graalvm.nativeimage.c.struct.CField "id"
              org.graalvm.nativeimage.c.struct.AllowWideningCast true} getId [] long]])

(gen-interface 
 :name ^{org.graalvm.nativeimage.c.CContext other_triple.Headers
         org.graalvm.nativeimage.c.function.CLibrary "triple"
         org.graalvm.nativeimage.c.struct.CStruct "triple_t"}
 wat.cool.OOTriple
 :extends [org.graalvm.word.PointerBase]
 :methods [[^{org.graalvm.nativeimage.c.struct.CFieldAddress "subject"} subject [] wat.cool.OOValue]])

(gen-class2
 :name ^{org.graalvm.nativeimage.c.CContext other_triple.Headers
         org.graalvm.nativeimage.c.function.CLibrary "triple"}
 wat.cool.OOTripletLib

 :methods [^:static ^:native [^{org.graalvm.nativeimage.c.function.CFunction
                                {:transition org.graalvm.nativeimage.c.function.CFunction$Transition/NO_TRANSITION}}
                              allocRandomTriple
                              []
                              wat.cool.OOTriple]
           ^:static ^:native [^{org.graalvm.nativeimage.c.function.CFunction
                                {:transition org.graalvm.nativeimage.c.function.CFunction$Transition/NO_TRANSITION}}
                              freeTriple
                              [wat.cool.OOTriple]
                              void]])

;; (comment
;;   public class TripletLib {
;;                            @CEnum("type_t")
;;                            enum DataType {
;;                                           I,
;;                                           F,
;;                                           S;

;;                                           @CEnumValue
;;                                           public native int getCValue();

;;                                           @CEnumLookup
;;                                           public static native DataType fromCValue(int value);
;;                                           }

;;                            @CFunction(transition = Transition.NO_TRANSITION)
;;                            public static native OOTriple allocRandomTriple();

;;                            @CFunction(transition = Transition.NO_TRANSITION)
;;                            public static native void freeTriple(OOTriple triple);

;;                            public static long getThing(OOTriple triple) {
;;                                                                          return triple.subject().getId();
;;                                                                          }
;;                            }
;;   )
