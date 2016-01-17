(ns geoip.db
  (:require [geoip.ip :as ip]
            ;[core.incubator :refer [dissoc-in]]

            ))

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


(def empty-db
  {:starts (sorted-map 0 nil)
   :ends (sorted-map (* 256 256 256 256) nil)})

(defn find-ip [db num]
  [
   (last (take-while #(<= % num) (keys (:starts db))))
   (first (drop-while #(< % num) (keys (:ends db))))
   ]

  )

(find-ip empty-db 9000)

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn remove-keys [hmap pred]
  (reduce
   (fn [acc k] (dissoc acc k))
   hmap
   (filter pred (keys hmap))))

(defn filter-db [db]
  (let [{:keys [starts ends]} db
        ends-to-remove (map (fn [[i c]] [(inc i) c]) (seq ends))
        matching (filter (fn [[i c]] (= c (starts i)))  ends-to-remove)
        culled (take (dec (count matching)) matching)
        [spos _] (first culled)]
    (reduce
     (fn [db [spos _]] (-> db
                       (dissoc-in [:starts spos])
                       (dissoc-in [:ends (dec spos)])))
     db culled)))

(defn add-range [db start end country]
  (let [start-slice (find-ip db start)
        [s1 s2] start-slice
        start-country (get-in db [:starts s1])
        end-slice (find-ip db end)
        [e1 e2] end-slice
        end-country (get-in db [:ends e2])]

    (let [db-added (-> db
               (assoc-in [:starts start] country)
               (assoc-in [:ends end] country)
               )]

      (if (= start-slice end-slice)
        (let [db-added (if (< end e2)
                         (-> db-added
                             (assoc-in [:starts (inc end)] end-country))
                         db-added)]

          (if (< s1 start)
            (-> db-added
                (assoc-in [:ends (dec start)] start-country))
            db-added))

        ;; overlaps
        (-> db-added
            (update-in [:starts] remove-keys #(< start % end))
            (update-in [:ends] remove-keys #(< start % end))
            (assoc-in [:ends (dec start)] start-country)
            (assoc-in [:starts (inc end)] end-country))))))

(def our-db (-> empty-db
                 (add-range 9000 90000 :au)
                 (add-range 90001 91000 :au)
                 (add-range 200000 300000 :sa)


                 filter-db))

(assoc-in empty-db [:starts (inc 10)] :us)



(defn add-indexes [db start end country]
  (-> db
      (add-range (ip/ip->int start) (ip/ip->int end) country)
      filter-db))

(defn lookup [db a b c d]
  (let [num (ip/parts->int [a b c d])]
    (get-in db [:starts (last (take-while #(< % num) (-> db :starts keys)))])))

(defn next-change [db num]
  (let [loc (first (drop-while #(<= % num) (-> db :starts keys)))]
    [loc (get-in db [:starts loc])]
    )
  )
