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

(defn ip->int
  "convert an IP number (represented as a string)
  into the 32 bit unsigned integer format for it"
  [ip]
  (let [[a b c d]
        (ip->parts ip)]
    (+ (* cubed-256 a) (* squared-256 b) (* 256 c) d)))


(defn int->ip
  "convert a 32 bit unsigned integer into a string
  formatted IP number"
  [num]
  (string/join
   "."
   (map str [(mod (int (/ num cubed-256)) 256)
             (mod (int (/ num squared-256)) 256)
             (mod (int (/ num 256)) 256)
             (mod num 256)])))


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
    (-> (sh "whois" ip)
        :out
        (string/split #"\n"))))


(defn lookup
  "Perform a single whois lookup on a single IP
  and return the [country start-ip ip-ranges next-ip]"
  [ip]
  (let [lines (get-whois ip)
        country-line (-> lines
                         (->> (filter #(.startsWith (string/lower-case %) "country:")))
                         first)]
    (if-not country-line
      ;; no whois record
      [nil ip [[ip 24]] (apply-ip ip #(+ % 256))]

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
        [country start (address-selectors start end) (next-ip end)]))))

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
  ([start final]
   (loop [ip (format "%d.0.0.0" start)
          coll []]
     (let [[a _ _ _] (ip->parts ip)]
       (if (> a final)
         coll
         (let [[country start mask end] (lookup ip)]
           (println ip)
           (recur end
                  (conj coll [country mask]))))))))


(defn -main
  "Build the geoip database"
  [& args]
  (let [fs (doall (map #(future (crawl %)) (range 1 32)))
        res (map deref fs)])

  ;(shutdown-agents)
  )
