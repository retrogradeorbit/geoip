(defproject geoip "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.domkm/whois "0.0.1"]
                 [com.taoensso/nippy "2.10.0"]
                 [org.clojure/tools.cli "0.3.1"]]
  :plugins [[lein-bin "0.3.4"]]
  :bin {:name "geoip"}
  :main ^:skip-aot geoip.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
