(ns vegetation-kotoba-golden-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [vegetation.cull :as cull]
            [vegetation.instance :as instance]
            [vegetation.lod :as lod]))

(defn- lod-code [tier]
  (double ({:detail 0 :billboard 1 :culled 2} tier)))

(defn- invoke-js [artifact calls]
  (let [source64 (.encodeToString (java.util.Base64/getEncoder)
                                  (.getBytes ^String (:source artifact) "UTF-8"))
        calls-json (json/write-str calls)
        script (str "const calls=" calls-json ";"
                    "import('data:text/javascript;base64," source64 "').then(m=>{"
                    "const k=m.instantiateKotoba({});"
                    "console.log(JSON.stringify(calls.map(([n,a])=>k[n](...a))));"
                    "}).catch(e=>{console.error(e);process.exit(99)})")
        result (shell/sh "node" "--input-type=module" "-e" script)]
    (is (zero? (:exit result)) (:err result))
    (mapv double (read-string (:out result)))))

(defn- invoke-wasm [artifact calls]
  (let [wasm64 (.encodeToString (java.util.Base64/getEncoder) (:bytes artifact))
        compiler-source (java.io.File. (.toURI (io/resource "kotoba/compiler/core.clj")))
        compiler-root (nth (iterate #(.getParentFile ^java.io.File %) compiler-source) 4)
        runtime-uri (str (.toURI (java.io.File. compiler-root "runtime/browser-host.mjs")))
        calls-json (json/write-str calls)
        script (str "const calls=" calls-json ";"
                    "import('" runtime-uri "').then(async m=>{"
                    "const h=await m.instantiateKotoba(Buffer.from('" wasm64 "','base64'));"
                    "console.log(JSON.stringify(calls.map(([n,a])=>h.instance.exports[n](...a))));"
                    "}).catch(e=>{console.error(e);process.exit(99)})")
        result (shell/sh "node" "--input-type=module" "-e" script)]
    (is (zero? (:exit result)) (:err result))
    (mapv double (read-string (:out result)))))

(deftest vegetation-spatial-goldens-agree-across-targets
  (let [source (slurp "src/vegetation_golden.kotoba")
        calls [["grass-lod-code" [10.0]] ["grass-lod-code" [25.0]]
               ["grass-lod-code" [60.0]] ["palm-lod-code" [100.0]]
               ["palm-lod-code" [400.0]] ["palm-lod-code" [600.0]]
               ["xz-distance" [13.0 -2.0 1.0 3.0]]
               ["patch-in-range-code" [16.0 16.0 40.0 32.0]]
               ["patch-in-range-code" [160.0 160.0 40.0 32.0]]
               ["placement-eligible-code" [20.0 0.1 0.8]]
               ["placement-eligible-code" [120.0 0.1 0.8]]
               ["placement-eligible-code" [20.0 0.6 0.8]]
               ["placement-eligible-code" [20.0 0.1 0.1]]]
        expected [(lod-code (lod/classify-lod 10.0 :grass))
                  (lod-code (lod/classify-lod 25.0 :grass))
                  (lod-code (lod/classify-lod 60.0 :grass))
                  (lod-code (lod/classify-lod 100.0 :palm-tree))
                  (lod-code (lod/classify-lod 400.0 :palm-tree))
                  (lod-code (lod/classify-lod 600.0 :palm-tree))
                  13.0 1.0 0.0 1.0 0.0 0.0 0.0]
        js-artifact (compiler/compile-source source :js-kotoba-v1)
        wasm-artifact (compiler/compile-source source :wasm32-browser-kotoba-v1)
        reference (mapv (fn [[name args]]
                          (ir/execute (:kir js-artifact) (symbol name) args))
                        calls)
        js-values (invoke-js js-artifact calls)
        wasm-values (invoke-wasm wasm-artifact calls)]
    ;; The public CLJC culler independently exercises the same 5-12-13 XZ
    ;; distance and confirms that the canonical grass instance is visible.
    (is (= [0] (cull/cull-by-distance
                [(instance/make-instance {:position [13.0 0.0 -2.0]
                                          :scale 1.0 :rotation 0.0 :species 0.0
                                          :wind-phase 0.0 :color-tint 0.0})]
                1.0 3.0 1)))
    (is (= expected reference))
    (is (= expected js-values))
    (is (= expected wasm-values))
    (is (= js-values wasm-values))
    (is (= :kotoba.floating-point/ieee-754-f32-f64-v7
           (:floating-point-policy js-artifact)))
    (is (= #{} (set (:effects (:kir js-artifact)))))))
