(ns geoip.core
  (:require [domkm.whois :refer [whois]]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]])
  (:gen-class))

(def ^:dynamic *use-jruby* false)

(def squared-256 (bit-shift-left 1 16))
(def cubed-256 (bit-shift-left 1 24))

;; map the values of the host part set to all '1'
;; to the hostmask length value
(def xor->hostmask
  (into {}
        (for [n (range 1 32)]
          [(dec (bit-shift-left 1 n)) n])))


(defn ip->parts
  "convert an IP number as a string into a vector
  of its parts. eg 10.1.4.2 -> [10 1 4 2]"
  [ip]
  (-> ip
      (string/split #"\.")
      (->> (map #(Integer/parseInt %)))
      vec))


(defn parts->int
  "Convert a vector length 4 into an int"
  [[a b c d]]
  (+ (* cubed-256 a) (* squared-256 b) (* 256 c) d))


(defn int->parts
  "convers an int into a vector length 4"
  [num]
  [(mod (int (/ num cubed-256)) 256)
   (mod (int (/ num squared-256)) 256)
   (mod (int (/ num 256)) 256)
   (mod num 256)])


(defn ip->int
  "convert an IP number (represented as a string)
  into the 32 bit unsigned integer format for it"
  [ip]
  (-> ip ip->parts parts->int))


(defn int->ip
  "convert a 32 bit unsigned integer into a string
  formatted IP number"
  [num]
  (string/join
   "."
   (map str (int->parts num))))


(defn apply-ip
  "apply a function to the ip number as an int.
  takes and returns the ip as a string"
  [ip func]
  (-> ip ip->int func int->ip))


(defn next-ip
  "add 1 to the ip. So 10.0.0.1 becomes 10.0.0.2
  and 10.20.255.255 becomes 10.21.0.0"
  [ip]
  (apply-ip ip inc))


(defn netmask
  "Given the start and end IP of a CIDR block,
  return the netmask (must be exact)"
  [start end]
  (- 32
     (xor->hostmask
      (bit-xor
       (ip->int start)
       (ip->int end)))))


(defn closest-netmask
  "Given the start abd end IP of an arbitrary
  range, return the netmask that will encompass the
  most IP numbers in the range (but will be
  incomplete)"
  [start end]
  (- 32
     (xor->hostmask
      (first
       (filter #(<= % (bit-xor
                       (ip->int start)
                       (ip->int end)))
               (-> xor->hostmask keys sort reverse))))))


(defn host-bits-full
  "Given the netmask, return the
  number with the host bits all filled.
  eg, for netmask 24, the value returned
  is 255"
  [netmask]
  (dec (bit-shift-left 1 (- 32 netmask))))


(defn next-address [ip netmask]
  (-> ip
      ip->int
      (+ (host-bits-full netmask))
      inc
      int->ip))


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

         (if (> num final)
           coll

           (recur (inc (ip->int next)) skip
                  (conj coll [country start end]))))))))

(defn -main
  "Build the geoip database"
  [& args]
  ;; all tasks need to be done
  (let [nums (for [a (range 1 255)
                   b (range 0 16)                   ]
               [a b])
        initial (count nums)]

    (reset! tasks  (into #{} nums))

    ;; spawn workers
    (let [threads 1024
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
                  (when (< diff total)
                    (recur)))))

      (let [derefed (doall (map deref results))]
        (println derefed))
      ))

  ;; is this even needed?
  (shutdown-agents)
  )
