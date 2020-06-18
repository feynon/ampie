(ns ampie.db
  (:require ["dexie" :default Dexie]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn init-db [db]
  (-> (. db (version 2))
    (. stores
      #js {:visits     "&visitHash, normalizedUrl, firstOpened, url"
           :closedTabs "++objId"
           :seenUrls   "&normalizedUrl"
           :links      "&normalizedUrl"})))

(defstate db
  :start (doto (Dexie. "AmpieDB") init-db)
  :stop (doto @db (.close)))
