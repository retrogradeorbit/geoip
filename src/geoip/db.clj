(ns geoip.db
  (:require [geoip.ip :as ip]
            ;[core.incubator :refer [dissoc-in]]

            ))

;; in memory database that stores mappings of ip spaces to their
;; country.

(def empty-db
  {:starts (sorted-map 0 nil)
   :ends (sorted-map (* 256 256 256 256) nil)})

(defn find-ip [db num]
  [
   (last (take-while #(<= % num) (keys (:starts db))))
   (first (drop-while #(< % num) (keys (:ends db))))
   ]

  )

;; (find-ip empty-db 9000)

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
