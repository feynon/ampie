(ns ampie.background.core
  (:require [ampie.background.messaging :as background.messaging]
            [ampie.db :refer [db]]
            [ampie.links]
            [ampie.background.backend]
            [ampie.background.link-cache-sync]
            [ampie.tabs.monitoring :as tabs.monitoring]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]
            [clojure.string :as string]
            [taoensso.timbre :as log]))

(defonce active-tab-interval-id (atom nil))

(defn handle-shortcut [command]
  (case command
    "amplify_page"
    (background.messaging/amplify-current-tab)))

(defstate shortcut-handler
  :start (.. browser -commands -onCommand
           (addListener handle-shortcut))
  :stop (.. browser -commands -onCommand
          (removeListener handle-shortcut)))

(defn ^:dev/before-load remove-listeners []
  (log/info "Removing listeners")

  (mount/stop)

  (. js/window clearInterval @active-tab-interval-id)
  (reset! active-tab-interval-id nil)

  (tabs.monitoring/stop))


(defn ^:dev/after-load init []
  (mount/start)

  (.. browser -runtime -onInstalled
    (addListener
      (fn [^js details]
        (when (or (= (.-reason details) "install")
                (= (.-reason details) "update"))
          (.. browser -tabs
            (create #js {:url "https://ampie.app/hello"}))))))

  #_(.. browser -tabs
      (query #js {} process-already-open-tabs))

  (when (some? @active-tab-interval-id)
    (. js/window clearInterval @active-tab-interval-id)
    (reset! active-tab-interval-id nil))

  (reset! active-tab-interval-id
    (. js/window setInterval
      tabs.monitoring/check-active-tab
      1000))

  (tabs.monitoring/start))
