(defproject jmxspy "0.1.1"
:description
 "Downloads JMS statistics and pushes them to Influx"
 :url
 "https://github.com/l3nz/jmx-spy"
 :license
 {:name "Eclipse Public License",
  :url "http://www.eclipse.org/legal/epl-v10.html"}
 :dependencies
 [[org.clojure/clojure "1.9.0"]
  [cli-matic "0.3.6"]
  [say-cheez "0.1.1"]
  [org.clojure/java.jmx "0.3.4"]
  [lenz/capacitor "0.6.0.2"]
  [orchestra "2019.02.06-1"]
  [cheshire "5.8.0"]]
 :main jmxspy.core
 :aot :all
 :global-vars {*warn-on-reflection* false, *assert* true}
 :plugins [[lein-codox "0.10.3"]
           [lein-eftest "0.5.1"]
           [lein-cljfmt "0.5.7"]]
 :eftest
 {:multithread? true,
  :report eftest.report.junit/report,
  :report-to-file "target/junit.xml"})

