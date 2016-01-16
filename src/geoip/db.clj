(ns geoip.db
  (:require [geoip.ip :as ip]))

;; in memory database that stores mappings of ip spaces to their
;; country.

;; data format for a.b.c.* is {a {b {c {k v}}}} where k v is key and
;; vector of final dictionary. k here is the start ip 'd' number as an
;; int and the v here is the country keyword :au

(defn add-index [db a b c start end country]
  (let [end+1 (inc end)]
    (-> db
        ;; add the country start
        (assoc-in [a b c start] country)

        ;; add the end marker
        (update-in
         [a b c]
         (fn [d-map]
           (if (or (= end+1 256)
                   (contains? d-map end+1))
             ;; unchanged
             d-map

             ;; if there is a prior marker, put that in there, else nil
             (assoc d-map end+1
                    (->> d-map keys sort
                         (take-while #(< % start))
                         last d-map)))))

        ;; remove any keys between start and end
        (update-in
         [a b c]
         (fn [d-map]
           (->> d-map keys
                (filter #(< start % end+1))
                (reduce dissoc d-map)))))))

(defn lookup [db a b c d]
  (let [d-map (get-in db [a b c] {})
        k (last (take-while #(<= % d) (sort (keys d-map))))]
    (d-map k)))

(defn make-triplet [num]
  [(mod (int (/ num ip/squared-256)) 256)
   (mod (int (/ num 256)) 256)
   (mod num 256)])

(defn indexes-between [start end]
  (let [s (ip/ip->int start)
        e (ip/ip->int end)
        s-base (-> s (/ 256) int)
        e-base (-> e (/ 256) int)
        s-rem (mod s 256)
        e-rem (mod e 256)
        base-range (- e-base s-base)]
    (map vector
         (map make-triplet (range s-base (inc e-base)))
         (conj (repeat base-range 0) s-rem)
         (conj (vec (repeat base-range 255)) e-rem))))

(defn add-indexes [db start end country]
  (reduce (fn [db [[a b c] s e]]
            (add-index db a b c s e country))
          db
          (indexes-between start end)))



(comment
  (def test-db
    {10
     {0
      {0 {0 :au 128 :us}
       1 {0 :us}
       2 {0 :us 192 nil}}}})

  (-> (add-index test-db 10 10 10 0 255 :au)
      (add-index 10 10 10 64 191 :us)
      (add-index 10 10 10 0 255 :au)
      (add-indexes "20.0.0.0" "20.20.2.128" :zim)

      (lookup 20 20 1 0))


  ({:a :A :b :B} nil)

)
