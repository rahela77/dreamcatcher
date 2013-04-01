(defproject dreamcatcher "1.0.2-SNAPSHOT"
            :description "Dreamcatcher is a realy small library that
               strives to simulate state machine behavior."
            :dependencies [[org.clojure/clojure "1.4.0"]
                           [com.cemerick/clojurescript.test "0.0.2"]]
            :plugins [[lein-cljsbuild "0.3.0"]]
            :source-path "src"
            :cljsbuild {:crossovers [dreamcatcher]
                        :crossover-jar true
                        :crossover-path "src-cljs";;})
                        :builds {:dev 
                                 {:source-paths ["src-cljs" "src"]
                                  :jar true
                                  :compiler {:output-to "js/dreamcatcher.js"
                                             ;;:optimizations :advanced
                                             :optimizations :simple
                                             :pretty-print true}}}})
