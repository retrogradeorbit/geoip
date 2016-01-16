(ns geoip.core
  (:require [domkm.whois :refer [whois]]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [geoip.ip :refer [ip->int int->ip closest-netmask next-address
                              apply-ip next-ip parts->int int->parts
                              ip->parts squared-256 cubed-256
                              ]]
            [geoip.db :as db]
            [taoensso.nippy :as nippy]
)
  (:import [java.util.concurrent Executors]
           [java.io DataInputStream DataOutputStream]
           )
  (:gen-class))

(def ^:dynamic *use-jruby* false)




(defn address-selectors
  "given an arbitrary start and end ip returned from whois,
  decompose this into a vector of ip/netmask pairs."
  [start end]
  (loop [start start
         end end
         coll []]
    (if (> (ip->int start)
           (ip->int end))
      coll
      (let [mask (closest-netmask start end)]
        (recur
         (next-address start mask)
         end
         (conj coll [start mask]))))))


(defn get-whois
  "issue a whois query. Return a sequence of line strings."
  [ip]
  (if *use-jruby*
    (try
      (-> ip whois :parts first :body
          (string/split #"\n"))
      (catch org.jruby.embed.InvokeFailedException e
        ;; JRuby whois failed. Lets fall back to normal whois command!
        (-> (sh "whois" ip)
            :out
            (string/split #"\n"))))
    (let [{:keys [out exit err]} (loop []
                               (let [res
                                     (try
                                       (sh "whois" ip)
                                       (catch java.io.IOException e
                                         (println "sleeping...")
                                         (Thread/sleep (rand-nth [300 500 700 1000 1500]))
                                         (println "again!")
                                         nil))]
                                 (if res
                                   res
                                   (recur))))]
      #_ (when-not (zero? exit)
        (println exit "=>" err)
        (println out))
      (string/split out #"\n"))))


(defn lookup
  "Perform a single whois lookup on a single IP
  and return the [country start-ip ip-ranges next-ip]"
  [ip & [skip]]
  ;(println "lookup" ip)
  (let [lines (get-whois ip)
        country-line (-> lines
                         (->> (filter #(.startsWith (string/lower-case %) "country:")))
                         first)]
    (if-not country-line
      ;; no whois record
      (do
        ;(println "no-whois" ip skip)
        [nil ip (apply-ip ip #(+ % (or skip 1)))
         (apply-ip ip #(+ % (or skip 1))) (* 2 skip)])

      ;; parse whois
      (let [country (-> country-line
                        (string/split #"\s+")
                        second
                        string/lower-case
                        keyword)
            [start end] (-> lines
                            (->> (filter #(or
                                           (.startsWith % "inetnum")
                                           (.startsWith % "NetRange"))))
                            first
                            (string/split #"\s+")
                            rest
                            (->> (take-nth 2)))]
        ;(println start " - " end)
        [country start end (next-ip end) 1]))))

(def tasks (atom #{}))

(defn get-triplets-between [start end]
  (let [s (-> start parts->int dec (/ squared-256) int inc)
        e (-> end parts->int inc (/ squared-256) int)]
    (map
     #(let [[a b _ _]
            (int->parts (* squared-256 %))]
        [a b])
     (range s  e))))

(defonce data (atom {}))


(defn crawl
  "start and end are the first number in the ip4 quad; so 'a' in
  a.b.c.d
  this gathers all the records from start to end. This is used
  to divide and conquer the address space with threads

  so (crawl 1) will fill everything from 1.0.0.0 -> 1.255.255.255
  (crawl 4 5) will fill everything from 4.0.0.0 -> 5.255.255.255
  "
  ([start]
   (crawl start start))
  ([[sa sb] [fa fb]]
   (let [final (parts->int [fa fb 255 255])]
     (loop [num (parts->int [sa sb 0 0])
            skip 1
            coll []]
       (let [[country start end next skip] (lookup (int->ip num) skip)
             triplets (get-triplets-between (ip->parts start) (ip->parts end))

             ]
         (when (seq triplets)
           (swap! tasks #(reduce disj % triplets)))

         ;; merge output data into the shared database
         (swap! data db/add-indexes start end country)

         (if (> num final)
           coll

           (recur (inc (ip->int next)) skip
                  (conj coll [country start end]))))))))




(def cli-options
  [
   ["-h" "--help"]
   ["-v" "--version"]
   ["-s" "--start IP" "Starting IP number"
    :default "1.0.0.0"
    ;:validate [#()]
    ]
   ["-e" "--end IP" "Ending IP number"
    :default "2.0.0.0"]
   ["-d" "--db DBFILE" "The database file to use"
    :default "db.edn"]
   ["-q" "--query IP" "Lookip an ip in the database"]
   ["-t" "--threads COUNT" "The maximum number of whois trawling threads"
    :default "512"]
   ])

(def version "0.0.1")

(defn load-db [filename]
  (println "loading..." filename)
  (edn/read-string (slurp filename))
)

(defn load-db! [filename]
  (reset! data (load-db filename)))

(defn save-db! [db filename]
  (println "saving..." filename)
  (spit filename (prn-str db)))

(defn -main
  "Build the geoip database"
  [& args]
  ;; all tasks need to be done
  (let [{:keys [options summary]} (cli/parse-opts args cli-options)]
    (cond
      (:version options)
      (println "Version:" version)

      (:help options)
      (println summary)

      (:query options)
      (println (:query options) "=>"
               (apply db/lookup
                      (-> options :db load-db)
                      (ip->parts (:query options))))

      :else
      (let [start (:start options)
            end (:end options)
            si (-> start ip->int (/ squared-256) int)
            ei (-> end ip->int (/ squared-256) int)

            parts (for [n (range si ei)]
                    [
                     (-> n (/ 256) int)
                     (mod n 256)
                     ])

            nums parts
            initial (count nums)]

        (when (.exists (io/file (:db options)))
          (load-db! (:db options)))
        (reset! tasks  (into #{} nums))

        ;; spawn workers
        (let [threads (Integer/parseInt (:threads options))
              results
              (doall (for [n (range threads)]
                       (future
                         (loop []
                           (let [t @tasks
                                 r (first t)]
                             (when r
                               (swap! tasks disj r)
                                        ;(println "crawling" r)
                                        ;(println "result:" (crawl r))
                               (crawl r)
                               (recur)))))))]
          (future (loop []
                    (let [real (count (filter realized? results))
                          unreal (- threads real)
                          total initial
                          left (count @tasks)
                          new (+ unreal
                                 left)
                          diff  (max 0 (- total new))]

                                        ;(t/move-cursor term 0 0)
                      (print
                       (str "\r"
                            (format "%2.2f%%" (float (* 100 (/ diff total))))
                            (format " (%d/%d)" diff total)
                            "  "
                            ))
                      (flush)

                      (Thread/sleep 500)
                      (if (< diff total)
                        (recur)
                        (println)))))

          (let [derefed (doall (map deref results))]

            #_ (with-open [w (io/output-stream "database.nippy")]
                 (nippy/freeze-to-out! (DataOutputStream. w) @data))

            (save-db! @data (:db options))
            )))))

  ;; is this even needed?
  (shutdown-agents)
  )




;;
;; % This is the RIPE Database query service.
;; % The objects are in RPSL format.
;; %
;; % The RIPE Database is subject to Terms and Conditions.
;; % See http://www.ripe.net/db/support/db-terms-conditions.pdf

;; %ERROR:306: connections exceeded
;; %
;; % Number of connections from a single IP address
;; % has exceeded the maximum number allowed (3).

;; % This query was served by the RIPE Database Query Service version 1.83.1 (DB-3)





;; % This is the RIPE Database query service.
;; % The objects are in RPSL format.
;; %
;; % The RIPE Database is subject to Terms and Conditions.
;; % See http://www.ripe.net/db/support/db-terms-conditions.pdf

;; %ERROR:201: access denied for YOUR.IP.ADDRESS.HERE
;; %
;; % Queries from your IP address have passed the daily limit of controlled objects.
;; % Access from your host has been temporarily denied.
;; % For more information, see
;; % http://www.ripe.net/data-tools/db/faq/faq-db/why-did-you-receive-the-error-201-access-denied

;; % This query was served by the RIPE Database Query Service version 1.83.1 (DB-3)
