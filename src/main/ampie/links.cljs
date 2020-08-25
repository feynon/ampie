(ns ampie.links
  (:require [ampie.db :refer [db]]
            [taoensso.timbre :as log]
            [ampie.background.backend :as backend]
            [ampie.interop :as i]
            [mount.core :refer [defstate]]))

(defstate large-prefixes-cache :start (atom {}))

(defn group-by-source [seen-at-map]
  (group-by
    (fn [[id info]]
      (if (string? info)
        info
        (or (:source info) (:seen-at info))))
    seen-at-map))

(defn transform-seen-at [seen-at]
  (let [int-ids (map (fn [[k v]] [(js/parseInt (name k)) v]) seen-at)
        grouped (group-by-source int-ids)
        hn      (concat (grouped "hnc") (grouped "hn"))
        twitter (concat (grouped "tf") (grouped "tl"))]
    (merge (when (seq hn) {:hn hn})
      (when (seq twitter) {:twitter twitter})
      (when (seq (grouped "vf")) {:visits (grouped "vf")}))))

(defn get-links-with-nurl
  "Returns a promise that resolves with a clojure vector
  `[[server-link-id source]+]` - all the entries for the corresponding
  normalized url from the links table."
  [normalized-url]
  (-> (.-links @db) (.get normalized-url)
    (.then #(-> % i/js->clj :seen-at))
    (.then transform-seen-at)))

(defn get-links-with-nurls
  "Returns a promise that resolves with a seq
  `({origin -> links}+)` - one map for each given normalized url."
  [normalized-urls]
  (-> (.-links @db) (.bulkGet (clj->js normalized-urls))
    (.then #(->> % i/js->clj (map :seen-at)))
    (.then #(map transform-seen-at %))))

(defn get-nurls-badge-sightings-counts
  "Returns a seq: for each normalized url, either nil
  or a number denoting the number of times the given
  normalized url has been seen before."
  [normalized-urls]
  (-> (.-seenBadgeLinks @db) (.bulkGet (clj->js normalized-urls))
    (.then #(->> % i/js->clj (map :count)))))

(defn inc-nurl-badge-sightings-counts
  "Increments the number of sightings for each of the given normalized url."
  [normalized-url]
  (let [now (.getTime (js/Date.))]
    (-> (.-seenBadgeLinks @db)
      (.get normalized-url)
      (.then (fn [result]
               (let [{:keys [count]} (i/js->clj result)]
                 (i/clj->js
                   {:normalized-url normalized-url
                    :count          (inc count)}))))
      (.then (fn [to-put]
               (-> (.-seenBadgeLinks @db) (.put to-put)))))))

(defn inc-nurls-badge-sightings-counts
  "Increments the number of sightings for each of the given normalized urls."
  [normalized-urls]
  (let [unique-normalized-urls (vec (set normalized-urls))
        now                    (.getTime (js/Date.))]
    (-> (.-seenBadgeLinks @db)
      (.bulkGet (clj->js unique-normalized-urls))
      (.then (fn [results]
               (->> results i/js->clj
                 (map (fn [nurl {:keys [count]}]
                        {:normalized-url nurl
                         :count          (inc count)})
                   unique-normalized-urls)
                 i/clj->js)))
      (.then (fn [to-put]
               (-> (.-seenBadgeLinks @db) (.bulkPut to-put)))))))

(defn link-ids-to-info
  "Accepts a seq of [link-id {:source or :seen-at}] maps,
  and returns a Promise that resolves to a seq of corresponding
  links info: tweet urls and hn stories/comments urls.
  link-id has to be of type int."
  [links]
  (if (seq links)
    (let [local-link->server-format
          (fn [[link-id {:keys [source seen-at] :as link-info}]]
            {:id     link-id
             :origin (case (or source seen-at)
                       "vf"  :visit
                       "hn"  :hn_story
                       "hnc" :hn_comment
                       "tf"  :tf
                       "tl"  :tl
                       nil)})
          links (map local-link->server-format links)]
      (.catch
        (backend/link-ids-to-info links)
        #(assoc % :hn nil :twitter nil)))
    (js/Promise.resolve {})))

(defn get-links-starting-with
  ([normalized-url-prefix]
   (get-links-starting-with normalized-url-prefix 0))
  ([normalized-url-prefix page]
   (get-links-starting-with normalized-url-prefix 50 (* page 50)))
  ([normalized-url-prefix limit offset]
   (if-let [result (@@large-prefixes-cache normalized-url-prefix)]
     (js/Promise.resolve (->> result (drop offset) (take limit)))
     (-> (.-links @db) (.where "normalizedUrl")
       (.startsWith (str normalized-url-prefix "."))
       (.or "normalizedUrl")
       (.startsWith (str normalized-url-prefix "/"))
       (.or "normalizedUrl")
       (.equals normalized-url-prefix)
       (.reverse)
       (.sortBy "count")
       (.then i/js->clj)
       (.then (fn [links] (map #(update % :seen-at transform-seen-at) links)))
       (.then
         (fn [result]
           (when (> (count result) 100)
             (swap! @large-prefixes-cache assoc normalized-url-prefix result))
           (->> result (drop offset) (take limit))))))))

(defn delete-seen-at [normalized-url link-id]
  (->
    (.-links @db)
    (.where "normalizedUrl") (.equals normalized-url)
    (.modify
      (fn [^js visit-info ^js ref]
        (let [seen-at (dissoc (i/js->clj (.-seenAt visit-info))
                        (keyword (str link-id)))]
          (if (seq seen-at)
            (do
              (set! (.-seenAt visit-info) (i/clj->js seen-at))
              (set! (.-count visit-info) (dec (.-count visit-info))))
            (js-delete ref "value")))))))
