(ns geoip.core
  (:require [domkm.whois :refer [whois]]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [clojure.core.async :as async])
  (:gen-class)
  )

                                                                                                                  (def squared (bit-shift-left 1 16))
(def cubed (bit-shift-left 1 24))
(def xor->netmask
  (into {}
        (for [n (range 1 32)]
          [(dec (bit-shift-left 1 n)) n])))

(defn ip->parts [ip]
  (-> ip
      (string/split #"\.")
      (->> (map #(Integer/parseInt %)))
      vec))

(defn ip->int [ip]
  (let [[a b c d]
        (ip->parts ip)]
    (+ (* cubed a) (* squared b) (* 256 c) d)))


(defn int->ip [num]
  (string/join
   "."
   (map str [(mod (int (/ num cubed)) 256)
             (mod (int (/ num squared)) 256)
             (mod (int (/ num 256)) 256)
             (mod num 256)])))


(defn apply-ip [ip func]                                                                                            (-> ip ip->int func int->ip))


(defn next-ip [ip]
  (apply-ip ip inc))


(defn netmask [start end]
  (- 32
     (xor->netmask
      (bit-xor                                                                                                           (ip->int start)                                                                                                   (ip->int end)))))


(defn closest-netmask [start end]
  (- 32
     (xor->netmask
      (first
       (filter #(<= % (bit-xor
                       (ip->int start)
                       (ip->int end)))
               (-> xor->netmask keys sort reverse))))))


(defn host-bits-full [netmask]
  (dec (bit-shift-left 1 (- 32 netmask))))


(defn next-address [ip netmask]
  (-> ip
      ip->int
      (+ (host-bits-full netmask))
      inc
      int->ip))


(defn address-selectors [start end]
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


(defn get-whois [ip]
  (-> (sh "whois" ip)
          :out
          (string/split #"\n"))

  #_ (try
    (-> ip whois :parts first :body
        (string/split #"\n"))
    (catch org.jruby.embed.InvokeFailedException e
      ;; JRuby whois failed. Lets fall back to normal whois command!
      (-> (sh "whois" ip)
          :out
          (string/split #"\n")))))


(defn lookup [ip]
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
  "I don't do a whole lot ... yet."
  [& args]
  ;(println (prn-str (pmap #(crawl (* 4 %) (+ 3 (* 4 %))) (range 0 64))))





  (println
   (prn-str
    (pmap crawl (range 1 256))))

  (shutdown-agents)

  ;; (loop [ip "1.0.0.0"]
  ;;   (let [[country start mask end] (lookup ip)]
  ;;     (println country start mask "(next" end ")")
  ;;     (recur end)))
  )
