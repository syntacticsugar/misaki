(ns misaki.test.config
  (:use misaki.config
        [misaki.util.file :only [normalize-path]]
        [misaki.tester :only [set-base-dir! deftest* bind-config]]
        [clj-time.core :only [date-time year month day]]
        clojure.test)
  (:require [clojure.java.io :as io])
  (:import [java.io FileNotFoundException]))

(defn dummy0 [] ())
(defn dummy1 [a] ())
(defn dummy2 [a b] ())
(defn dummy3 ([a] ()) ([a b c] ()))
(defn dummy4 [a & b] ())


(set-base-dir! "test/")

; read-config
(deftest* read-config-test
  (testing "normal pattern"
    (let [config (read-config)]
      (are [x y] (= x y)
        "public/"   (:public-dir config)
        "template/" (:template-dir config)
        "_posts/"   (:post-dir config)
        "_layouts/" (:layout-dir config))))

  (testing "error pattern"
    (binding [*base-dir* "/foo/bar/"]
      (is (thrown? FileNotFoundException (read-config)))))

  (testing "specify default value"
    (binding [*base-dir* "/foo/bar/"]
      (is (= {} (read-config {}))))))

;;; load-compiler-publics-test
(deftest* load-compiler-publics-test
  (testing "default compiler"
    (let [c (load-compiler-publics "default")]
      (are [x y] (= x y)
        true      (every? #(contains? c %) '(-extension -config -compile))
        "default" (:name c))))

  (testing "unknown compiler(read default)"
    (let [c (load-compiler-publics "foo")]
      (are [x y] (= x y)
        true      (every? #(contains? c %) '(-extension -config -compile))
        "default" (:name c))))

  (testing "multiple compilers"
    (let [[c1 c2 :as cs] (load-compiler-publics ["default" "demo"])]
      (are [x y] (= x y)
        2         (count cs)
        "default" (:name c1)
        "demo"    (:name c2)
        true      (every? #(contains? c1 %) '(-extension -config -compile))
        true      (every? #(contains? c2 %) '(-extension -config -compile)))))
  
  )

;;; get-date-from-file
(deftest* get-date-from-file-test
  ; test config: #"(\d{4})[.](\d{1,2})[.](\d{1,2})[-_](.+)$"
  (testing "valid date"
    (let [date (get-date-from-file (io/file "2000.11.22-dummy.clj"))]
      (are [x y] (= x y)
        2000 (year date)
        11   (month date)
        22   (day date))))

  (testing "invalid date"
    (are [filename] (nil? (get-date-from-file (io/file filename)))
      "2000.11.xx.dummy.clj"
      "2000.xx.22-dummy.clj"
      "xxxx.11.22-dummy.clj"
      "2000.11.dummy.clj"
      "2000.dummy.clj"
      "dummy.clj"
      ""
      nil)))

;;; remove-date-from-name
(deftest* remove-date-from-name-test
  (is (= "dummy.clj" (remove-date-from-name "2000.11.22-dummy.clj"))))

;;; make-post-output-filename
(deftest* make-post-output-filename-test
  ; test config: "{{year}}-{{month}}/{{filename}}"
  (testing "filename with date"
    (let [file (io/file "2000.11.22-dummy.html")]
      (is (= "2000-11/dummy.html" (make-post-output-filename file)))))

  (testing "filename without date"
    (are [x y] (= x (make-post-output-filename (io/file y)))
      "-/foo.html"       "foo.html"
      "-/01.foo.html"    "01.foo.html"
      "-/01.02.foo.html" "01.02.foo.html")))

;;; make-output-filename
(deftest* make-output-filename-test
  (testing "normal template file"
    (is (= "foo.html" (make-output-filename (io/file "foo.html")))))

  (testing "post file"
    (is (= "2000-11/foo.html"
           (make-output-filename
             (io/file "test/template/_posts/2000.11.22-foo.html"))))))

;;; make-output-url
(deftest* make-output-url-test
  (let [file (io/file "foo.html")]
    (testing "default url-base"
      (is (= "/foo.html" (make-output-url file))))

    (testing "custom url-base"
      (bind-config [:url-base (normalize-path "/bar/baz")]
        (is (= "/bar/baz/foo.html" (make-output-url file)))))))

;;; absolute-path
(deftest* absolute-path-test
  (with-config
    (are [x y] (= x (absolute-path y))
      "/a.htm" "a.htm"
      "/bar/a.htm" "bar/a.htm"
      "/a.htm" "/a.htm"
      "/bar/a.htm" "/bar/a.htm"
      "http://localhost/a.htm" "http://localhost/a.htm"
      "https://localhost/a.htm" "https://localhost/a.htm")

    (bind-config [:url-base "/foo/"]
      (are [x y] (= x (absolute-path y))
        "/foo/a.htm" "a.htm"
        "/foo/a.htm" "/a.htm"
        "/foo/bar/a.htm" "/bar/a.htm"
        "/foo/bar/a.htm" "bar/a.htm"))))
