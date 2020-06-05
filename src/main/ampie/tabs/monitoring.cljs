(ns ampie.tabs.monitoring
  "Keeps track of all the tabs and transitions and
  saves them to indexeddb."
  (:require ["webextension-polyfill" :as browser]
            [ampie.tabs.core :as tabs :refer [open-tabs]]
            [ampie.visits :as visits]
            [ampie.tabs.event-handlers :as evt]
            [ampie.url :as url]))

(defn check-active-tab []
  (-> (.. browser -windows (getLastFocused #js {:populate true}))
    (.then
      (fn [window]
        (let [{tabs-list :tabs focused :focused :as window}
              (js->clj window :keywordize-keys true)
              {tab-id :id} (first (filter :active tabs-list))]
          (if focused
            (tabs/tab-in-focus! tab-id)
            (tabs/no-tab-in-focus!)))))))

(defn process-already-open-tabs [tabs]
  (let [tabs (js->clj tabs :keywordize-keys true)]
    (doseq [{:keys [id url title] :as tab} tabs]
      (let [evt {:tabId     id
                 :url       (url/clean-up url)
                 :title     title
                 :timeStamp (.getTime (js/Date.))}]
        (if (contains? @@open-tabs id)
          (evt/went-back-or-fwd-in-tab evt)
          (evt/restored-tab evt))))))

(defn stop []
  (.. browser -tabs -onUpdated
    (removeListener evt/tab-updated))
  (.. browser -windows -onFocusChanged
    (removeListener evt/window-focus-changed))
  (.. browser -tabs -onRemoved
    (removeListener evt/tab-removed))
  (.. browser -webNavigation -onHistoryStateUpdated
    (removeListener evt/history-state-updated))
  (.. browser -webNavigation -onCommitted
    (removeListener evt/committed))
  (.. browser -webNavigation -onCreatedNavigationTarget
    (removeListener evt/created-navigation-target)))

(defn start []
  (.. browser -tabs -onUpdated
    (addListener evt/tab-updated))
  (.. browser -windows -onFocusChanged
    (addListener evt/window-focus-changed))
  (.. browser -tabs -onRemoved
    (addListener evt/tab-removed))
  (.. browser -webNavigation -onHistoryStateUpdated
    (addListener evt/history-state-updated))
  (.. browser -webNavigation -onCommitted
    (addListener evt/committed))
  (.. browser -webNavigation -onCreatedNavigationTarget
    (addListener evt/created-navigation-target)))
