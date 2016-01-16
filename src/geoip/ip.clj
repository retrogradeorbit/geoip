(ns geoip.ip
  (:require [clojure.string :as string])
)

;; functions to process ip numbers, networks and netmasks

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
