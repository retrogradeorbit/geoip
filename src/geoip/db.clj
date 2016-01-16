(ns geoip.db)

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
           (if (contains? d-map end+1)
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
      (lookup 10 10 10 128))


  ({:a :A :b :B} nil)

)
