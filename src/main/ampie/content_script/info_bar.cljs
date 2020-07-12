(ns ampie.content-script.info-bar
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [ampie.interop :as i]
            [taoensso.timbre :as log]
            [ajax.core :refer [GET]]
            [clojure.string :as string]
            [ampie.components.basics :as b]
            [ampie.time]))

(defn tweet [{{:keys [screen_name]} :user
              {urls :urls}          :entities
              :keys                 [full_text created_at id_str]
              :as                   tweet-info}]
  [:div.tweet.row
   [:div.text
    (let [indices      (concat [0]
                         (mapcat :indices urls)
                         [(count full_text)])
          text-substrs (->> (partition 2 indices)
                         (map (fn [[start end]] (subs full_text start end))))
          links        (map-indexed
                         (fn [idx {:keys [expanded_url display_url]}]
                           ^{:key idx} [:a (b/ahref-opts expanded_url) display_url])
                         urls)
          result       (concat [(first text-substrs)]
                         (interleave links (rest text-substrs)))]
      result)]
   [:div.info
    [:div.author screen_name]
    [:a.date
     (b/ahref-opts (str "https://twitter.com/" screen_name "/status/" id_str))
     (ampie.time/timestamp->date (js/Date.parse created_at))]]])

(defn tweets [tweets-info]
  [:div.tweets.pane
   [:div.header [:span.icon.twitter-icon] "Tweets"]
   (if (= tweets-info :loading)
     [:div.loading "Loading"]
     (for [{:keys [id_str] :as tweet-info} tweets-info]
       ^{:key id_str} [tweet tweet-info]))])

(defn hn-story [{:keys [by title time score descendants id]
                 :as   story-info}]
  [:div.hn-story.row
   [:a.title (b/ahref-opts (str "https://news.ycombinator.com/item?id=" id)) title]
   [:div.info
    [:div.author by] [:div.n-comments (str descendants " comments")]
    [:div.score (str score " points")]
    [:div.date (ampie.time/timestamp->date (* time 1000))]]])

(defn hn-stories [hn-stories-info]
  [:div.hn-stories.pane
   [:div.header [:span.icon.hn-icon] "HN stories"]
   (if (= hn-stories-info :loading)
     [:div.loading "Loading"]
     (for [{:keys [id] :as story-info} hn-stories-info]
       ^{:key id} [hn-story story-info]))])

(defn seen-at [sources]
  (when (seq sources)
    [:div.seen-at.pane
     [:div.header [:span.icon.history-icon] "Previously seen at"]
     (for [{:keys [url visit-hash first-opened title] :as info} sources]
       ^{:key visit-hash}
       [:div.previous-sighting.row
        [:a.title (b/ahref-opts url) title]
        [:div.info
         [:div.domain (url/get-domain url)]
         [:div.date (ampie.time/timestamp->date first-opened)]]])]))

#_(defn mini-tags [reference-counts]
    (for [[source-name count] reference-counts]
      ^{:key source-name}
      [:div.mini-tag
       [(keyword (str "i.icon." (name source-name)))]
       count]))

(defn bottom-row
  [{:keys [normalized-url prefixes-info close-page-info
           show-prefix-info]}]
  (let [domain-len         (string/index-of normalized-url "/")
        url-parts          (url/get-parts-normalized normalized-url)
        url-parts          (map #(update % 0 dec)
                             (cons [1 0 (-> url-parts second last)]
                               (drop 2 url-parts)))
        domain-parts       (take-while #(<= (last %) domain-len) url-parts)
        reversed-url-parts (concat
                             (map (fn [[idx start end]]
                                    [idx (- domain-len end) (- domain-len start)])
                               (reverse domain-parts))
                             (drop (count domain-parts) url-parts))
        reversed-normalized-url
        (url/reverse-lower-domain normalized-url)]
    [:div.bottom-row
     (into [:div.url]
       (apply concat
         (for [[idx start end] reversed-url-parts
               :let
               [substr (subs reversed-normalized-url start end)
                info (nth prefixes-info idx)
                links (second info)
                prev-info (when (pos? idx)
                            (nth prefixes-info (dec idx)))
                prev-links (second prev-info)
                after (cond
                        (< end domain-len) "."
                        (= end domain-len) "/"
                        :else              (subs normalized-url end (inc end)))
                highlight?
                (or (> (count links) 1)
                  (and (= (count links) 1)
                    (not= normalized-url
                      (-> links first :normalized-url))))]]
           ;; TODO reproduce the bug with the lambda function being cached
           ;; even though highlight? changes if not including highlight?
           ;; into the key.
           [#^{:key (str start highlight?)}
            [:span.part
             (when highlight?
               {:on-click #(show-prefix-info info)
                :class    "highlight"})
             (js/decodeURI substr)]
            (when-not (empty? after) after)])))
     [:div.close {:on-click close-page-info}
      [:span.icon.close-icon]]]))

(defn window [{:keys [overscroll-handler window-atom]}]
  (letfn [(change-height [el delta-y]
            (let [current-height    (js/parseFloat
                                      (. (js/getComputedStyle el)
                                        getPropertyValue "height"))
                  min-height        28
                  lowest-child-rect (.. el -lastElementChild
                                      getBoundingClientRect)
                  children-height   (- (+ (. lowest-child-rect -y)
                                         (. lowest-child-rect -height))
                                      (.. el getBoundingClientRect -y))
                  max-height        children-height
                  new-height        (+ current-height
                                      delta-y)]
              (cond
                (< new-height min-height)
                (do (set! (.. el -style -height) (str min-height "px"))
                    (overscroll-handler :down (- min-height new-height)))

                (> new-height max-height)
                (do (set! (.. el -style -height) (str max-height "px"))
                    (overscroll-handler :up (- new-height max-height)))

                :else
                (do (set! (.. el -style -height) (str new-height "px"))
                    ;; Return false not to propagate the scroll
                    false))))]
    (into [:div.window
           {:ref
            (fn [el]
              (when el
                (swap! window-atom assoc :ref el)
                (swap! window-atom assoc
                  :update-height (partial change-height el))
                (set! (.-onwheel el)
                  (fn [evt] (change-height el (. evt -deltaY))))))}]
      (r/children (r/current-component)))))

(defn elements-stack []
  (let [children       (r/children (r/current-component))
        children-info  (for [_ (range (count children))] (r/atom {}))
        update-heights (fn [index direction delta]
                         (if (= index (dec (count children)))
                           ;; Don't propagate the scroll event
                           false
                           ((:update-height
                             @(nth children-info (inc index)))
                            (if (= direction :up)
                              delta
                              (- delta)))))]
    (into [:div.stack]
      (map #(vector window
              {:window-atom        %2
               :overscroll-handler (partial update-heights %3)}
              %1)
        (r/children (r/current-component)) children-info (range)))))

(defn mini-tag [source-key count]
  (if (pos? count)
    [:div.mini-tag
     [:span.icon {:class (str (name source-key) "-icon")}]
     count]
    [:div.mini-tag]))

(defn adjacent-link-row [{:keys [normalized-url seen-at]} prefix load-page-info]
  (let [reversed-normalized-url (url/reverse-lower-domain normalized-url)
        reversed-prefix         (url/reverse-lower-domain prefix)]
    [:div.row.adjacent-link
     [:div.url
      (into
        [:a (b/ahref-opts (str "http://" reversed-normalized-url))]
        (if-let [index (clojure.string/index-of reversed-normalized-url
                         reversed-prefix)]
          (let [start (subs reversed-normalized-url 0 index)
                end   (subs reversed-normalized-url (+ index (count prefix)))]
            [(js/decodeURI start)
             [:span.prefix (js/decodeURI reversed-prefix)]
             (js/decodeURI end)])
          [reversed-normalized-url]))]
     (let [grouped (group-by second seen-at)
           history (grouped "history")
           hn      (concat (grouped "hnc") (grouped "hn"))
           twitter (concat (grouped "tf") (grouped "tl"))]
       [:div.inline-mini-tags {:on-click #(load-page-info reversed-normalized-url)}
        [mini-tag :history (count history)]
        [mini-tag :hn (count hn)]
        [mini-tag :twitter (count twitter)]])]))

(defn adjacent-links [[normalized-url links] load-page-info]
  [:div.adjacent-links.pane
   [:div.header [:span.icon.domain-links-icon]
    "Links at " (url/reverse-lower-domain normalized-url)]
   (for [{link-url :normalized-url :as link} links]
     ^{:key link-url}
     [adjacent-link-row link normalized-url load-page-info])])

(defn info-bar [{:keys [page-info close-page-info show-prefix-info load-page-info]}]
  (let [{{:keys [history hn twitter]} :seen-at
         :keys
         [normalized-url prefixes-info prefix-info]}
        page-info]
    [:div.info-bar
     (into
       [elements-stack]
       (filter identity
         [(when history ^{:key :seen-at} [seen-at history])
          (when twitter ^{:key :tweets} [tweets twitter])
          (when hn ^{:key :hn-stories} [hn-stories hn])
          (when (and prefix-info (seq (second prefix-info)))
            ^{:key :prefix-info} [adjacent-links prefix-info load-page-info])]))
     [bottom-row
      {:normalized-url   normalized-url
       :prefixes-info    prefixes-info
       :close-page-info  close-page-info
       :show-prefix-info show-prefix-info}]]))

(defn mini-tags [{{domain-links :on-this-domain
                   :keys        [counts normalized-url show-weekly]} :page-info
                  :keys
                  [open-info-bar close-mini-tags]}]
  [:div.mini-tags {:on-click open-info-bar}
   (when show-weekly
     [:div.weekly
      {:on-click (fn [evt]
                   (.stopPropagation evt)
                   (.. browser -runtime
                     (sendMessage (clj->js {:type :open-weekly-links}))))}
      "Share the weekly links!"])
   (for [[source-key count] counts
         :when              (pos? count)]
     ^{:key source-key}
     [:div.mini-tag
      [:span.icon {:class (str (name source-key) "-icon")}]
      count])
   (when (or (> (count domain-links) 1)
           (and (pos? (count domain-links))
             (not= (-> domain-links first :normalized-url) normalized-url)))
     [:div.mini-tag
      [:span.icon.domain-links-icon]
      (let [count (count domain-links)]
        (if (>= count 50)
          (str count "+")
          count))])
   [:div.close {:on-click (fn [e] (.stopPropagation e) (close-mini-tags) nil)}
    [:span.icon.close-icon]]])

(defn hydrate-tweets [tweets]
  (.then
    (.. browser -runtime
      (sendMessage (clj->js {:type :get-tweets
                             :ids  (map (comp :tweet-id-str :info) tweets)})))
    #(js->clj % :keywordize-keys true)))

(defn hn-item-url [item-id]
  (str "https://hacker-news.firebaseio.com/v0/item/" item-id ".json"))

(defn hydrate-hn [hn-stories]
  (let [stories-ids (map (comp :item-id :info) hn-stories)]
    (js/Promise.all
      (for [story-id stories-ids]
        (js/Promise.
          (fn [resolve]
            (GET (hn-item-url story-id)
              {:response-format :json
               :keywords?       true
               :handler         #(resolve %)})))))))

(defn load-page-info [url pages-info]
  (log/info url)
  (letfn [(get-prefixes-info []
            (.then (.. browser -runtime
                     (sendMessage (clj->js {:type :get-prefixes-info
                                            :url  url})))
              #(js->clj % :keywordize-keys true)))]
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :get-url-info :url url})))
      (fn [js-url->where-seen]
        (let [{:keys [hn twitter history] :as seen-at}
              (js->clj js-url->where-seen :keywordize-keys true)
              new-page-info
              {:url            url
               :normalized-url (url/normalize url)
               :seen-at        {:history (seq history)
                                :twitter (when (pos? (count twitter)) :loading)
                                :hn      (when (pos? (count hn)) :loading)}
               :counts         {:history (count history)
                                :twitter (count twitter)
                                :hn      (count hn)}}]
          (let [idx (-> (swap! pages-info update :info-bars conj new-page-info)
                      :info-bars count dec)]
            (log/info idx url (:info-bars @pages-info))
            (.then (get-prefixes-info)
              (fn [prefixes-info]
                (swap! pages-info assoc-in
                  [:info-bars idx :prefixes-info] prefixes-info)
                (let [;; Find the entry for the domain among prefixes
                      domain-info (->> (remove #(clojure.string/includes?
                                                  (first %) "/") prefixes-info)
                                    last)
                      links       (second domain-info)]
                  ;; Show only if there are links besides the one the user is reading
                  ;; about
                  (when (or (> (count links) 1)
                          (and (= (count links) 1)
                            (not= (url/normalize url)
                              (-> links first :normalized-url))))
                    (swap! pages-info assoc-in
                      [:info-bars idx :prefix-info] domain-info)))))
            (when (seq twitter)
              (.then (hydrate-tweets twitter)
                #(swap! pages-info assoc-in [:info-bars idx :seen-at :twitter] %)))
            (when (seq hn)
              (-> (hydrate-hn hn)
                (.then (fn [x] (js/console.log x) x))
                (.then
                  #(swap! pages-info assoc-in [:info-bars idx :seen-at :hn] %))))))))))

(defn info-bars-and-mini-tags [{:keys [pages-info close-info-bar]}]
  (into [:div.info-bars-and-mini-tags]
    (into [(when (:open (:mini-tags @pages-info))
             ^{:key :mini-tags}
             [mini-tags {:page-info       (:mini-tags @pages-info)
                         :open-info-bar   #(load-page-info
                                             (.. js/document -location -href)
                                             pages-info)
                         :close-mini-tags #(swap! pages-info
                                             assoc-in [:mini-tags :open] false)}])]
      (when (seq (:info-bars @pages-info))
        [^{:key (count (:info-bars @pages-info))}
         [info-bar {:page-info       (last (:info-bars @pages-info))
                    :load-page-info  (fn [url] (load-page-info url pages-info))
                    :close-page-info (fn [] (swap! pages-info
                                              update :info-bars pop))
                    :show-prefix-info
                    (fn [prefix-info]
                      (swap! pages-info
                        update :info-bars
                        (fn [info-bars]
                          (assoc-in info-bars
                            [(dec (count info-bars)) :prefix-info]
                            prefix-info))))}]]))))

(defn remove-info-bar []
  (let [element (.. js/document -body (querySelector ".ampie-info-bar-holder"))]
    (when element (.. js/document -body (removeChild element)))))

(defn reset-current-page-info! [pages-info]
  (let [current-url (.. js/window -location -href)]
    (reset! pages-info {:mini-tags {:url            current-url
                                    :normalized-url (url/normalize current-url)}
                        :info-bars []})
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :should-show-weekly?})))
      #(swap! pages-info assoc-in [:mini-tags :show-weekly] %))
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :get-local-url-info
                                    :url  current-url})))
      (fn [js-url->where-seen]
        (let [{:keys [hn twitter history] :as seen-at}
              (js->clj js-url->where-seen :keywordize-keys true)]
          (swap! pages-info assoc-in [:mini-tags :seen-at :history] (seq history))
          (swap! pages-info assoc-in [:mini-tags :counts]
            {:history (count history)
             :twitter (count twitter)
             :hn      (count hn)})
          (when (or (seq history) (seq twitter) (seq hn))
            (swap! pages-info assoc-in [:mini-tags :open] true)))))
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :get-prefixes-info
                                    :url  current-url})))
      (fn [prefixes-info]
        (let [prefixes-info (js->clj prefixes-info :keywordize-keys true)
              domain-links  (-> (remove #(clojure.string/includes? (first %) "/")
                                  prefixes-info)
                              last
                              second)]
          (swap! pages-info assoc-in [:mini-tags :on-this-domain] domain-links)
          (when (pos? (count domain-links))
            (swap! pages-info assoc-in [:mini-tags :open] true)))))))

(defn display-info-bar
  "Mount the info bar element,
  display the mini tags if necessary, return the function
  that needs to be called with a url to be displayed in a new info bar."
  []
  (let [info-bar-div   (. js/document createElement "div")
        shadow-root-el (. js/document createElement "div")
        shadow         (. shadow-root-el (attachShadow #js {"mode" "open"}))
        shadow-style   (. js/document createElement "link")
        pages-info     (r/atom {})]
    (set! (.-rel shadow-style) "stylesheet")
    (.setAttribute shadow-root-el "style"  "display: none;")
    (set! (.-onload shadow-style) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/info-bar.css")))
    (set! (.-className shadow-root-el) "ampie-info-bar-holder")
    (set! (.-className info-bar-div) "info-bar-container")
    (reset-current-page-info! pages-info)
    (rdom/render [info-bars-and-mini-tags {:pages-info pages-info}] info-bar-div)
    (. shadow (appendChild shadow-style))
    (. shadow (appendChild info-bar-div))
    (retargetEvents shadow)
    (.. js/document -body (appendChild shadow-root-el))
    ;; Return a function to be called with a page url we want to display an info
    ;; bar for.
    {:reset-page (fn [] (reset-current-page-info! pages-info))
     :show-info  (fn [page-url] (load-page-info page-url pages-info))}))
