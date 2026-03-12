(ns orcpub.vtt.events
  (:require [cljs.reader :as reader]
            [clojure.string :as s]
            [cognitect.transit :as transit]
            [orcpub.dnd.e5.event-utils :as event-utils]
            [orcpub.route-map :as routes]
            [orcpub.vtt :as vtt]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-fx]]))

(defonce room-stream* (atom nil))
(defonce transit-reader (transit/reader :json))

(def authorization-headers event-utils/auth-headers)
(def url-for-route event-utils/url-for-route)
(def backend-url event-utils/backend-url)

(defn- response-body
  [response]
  (let [body (:body response)]
    (if (map? body)
      (or (:body body) body)
      body)))

(defn- response-room
  [response]
  (:room (response-body response)))

(defn- response-asset
  [response]
  (:asset (response-body response)))

(defn- room-page-route
  [room-id]
  (routes/match-route
   (routes/path-for routes/vtt-room-page-route :id room-id)))

(reg-fx
 :vtt/open-stream
 (fn [{:keys [room-id token]}]
   (when-let [current @room-stream*]
     (.close current))
   (let [path (routes/path-for routes/vtt-room-stream-route :id room-id)
         url (str (backend-url path) "?token=" (js/encodeURIComponent token))
         stream (js/EventSource. url)]
     (reset! room-stream* stream)
     (.addEventListener stream "open"
                        (fn [_]
                          (dispatch [::stream-open room-id])))
     (.addEventListener stream "error"
                        (fn [_]
                          (dispatch [::stream-error room-id])))
     (.addEventListener stream "room"
                        (fn [event]
                          (let [payload (reader/read-string (.-data event))]
                            (dispatch [::stream-event payload])))))))

(reg-fx
 :vtt/close-stream
 (fn [_]
   (when-let [current @room-stream*]
     (.close current)
     (reset! room-stream* nil))))

(reg-fx
 :vtt/upload-file
 (fn [{:keys [url token file on-success on-failure]}]
   (let [form-data (js/FormData.)]
     (.append form-data "file" file)
     (-> (js/fetch url
                   #js {:method "POST"
                        :headers #js {"Authorization" (str "Token " token)}
                        :body form-data})
         (.then (fn [response]
                  (let [status (.-status response)]
                    (-> (.text response)
                        (.then (fn [body-text]
                                 {:status status
                                  :body (transit/read transit-reader body-text)}))))))
         (.then (fn [response]
                  (if (<= 200 (:status response) 299)
                    (dispatch (conj on-success response))
                    (dispatch (conj on-failure response)))))
         (.catch (fn [_]
                   (dispatch (conj on-failure {:status 500
                                               :body {:message "Asset upload failed"}}))))))))

(reg-event-db
 ::set-create-room-name
 (fn [db [_ value]]
   (assoc-in db [:vtt :create-room-name] value)))

(reg-event-db
 ::set-member-username
 (fn [db [_ value]]
   (assoc-in db [:vtt :member-username] value)))

(reg-event-db
 ::set-chat-draft
 (fn [db [_ value]]
   (assoc-in db [:vtt :chat-draft] value)))

(reg-event-db
 ::set-asset-url
 (fn [db [_ value]]
   (assoc-in db [:vtt :asset-url] value)))

(reg-event-db
 ::select-scene
 (fn [db [_ scene-id]]
   (assoc-in db [:vtt :selected-scene-id] scene-id)))

(reg-event-db
 ::select-token
 (fn [db [_ token-id]]
   (assoc-in db [:vtt :selected-token-ids] (if token-id [token-id] []))))

(reg-event-db
 ::stream-open
 (fn [db _]
   (assoc-in db [:vtt :stream-status] :open)))

(reg-event-db
 ::stream-error
 (fn [db _]
   (assoc-in db [:vtt :stream-status] :error)))

(reg-event-fx
 ::stream-event
 (fn [{:keys [db]} [_ payload]]
   (condp = (:type payload)
     :connected {:db (assoc-in db [:vtt :stream-status] :open)}
     :room-updated (let [current-room-id (get-in db [:vtt :room-snapshot :db/id])]
                     (if (= current-room-id (:room-id payload))
                       {:dispatch [::load-room current-room-id false]}
                       {}))
     :presence-changed (let [current-room-id (get-in db [:vtt :room-snapshot :db/id])]
                         (if (= current-room-id (:room-id payload))
                           {:dispatch [::load-room current-room-id false]}
                           {}))
     {})))

(reg-event-fx
 ::load-rooms
 (fn [{:keys [db]} _]
   {:dispatch [:set-loading true]
    :http {:method :get
           :headers (authorization-headers db)
           :url (url-for-route routes/vtt-rooms-route)
           :on-success [::load-rooms-success]
           :on-failure [::request-failure]}}))

(reg-event-db
 ::load-rooms-success
 (fn [db [_ response]]
   (assoc-in db [:vtt :room-list] (vec (response-body response)))))

(reg-event-fx
 ::load-room
 (fn [{:keys [db]} [_ room-id open-stream?]]
   {:dispatch [:set-loading true]
    :http {:method :get
           :headers (authorization-headers db)
           :url (url-for-route routes/vtt-room-route :id room-id)
           :on-success [::load-room-success room-id open-stream?]
           :on-failure [::request-failure]}}))

(reg-event-fx
 ::load-room-success
 (fn [{:keys [db]} [_ room-id open-stream? response]]
   (let [room (response-room response)]
     (cond-> {:db (-> db
                      (assoc-in [:vtt :room-snapshot] room)
                      (assoc-in [:vtt :selected-scene-id] (::vtt/active-scene-id room))
                      (assoc-in [:vtt :selected-token-ids] []))}
       open-stream? (assoc :vtt/open-stream {:room-id room-id
                                             :token (-> db :user-data :token)})))))

(reg-event-fx
 ::create-room
 (fn [{:keys [db]} _]
   (let [name (get-in db [:vtt :create-room-name])]
     {:dispatch [:set-loading true]
      :http {:method :post
             :headers (authorization-headers db)
             :url (url-for-route routes/vtt-rooms-route)
             :transit-params {::vtt/name name}
             :on-success [::create-room-success]
             :on-failure [::request-failure]}})))

(reg-event-fx
 ::create-room-success
 (fn [{:keys [db]} [_ response]]
   (let [room (response-room response)
         room-id (:db/id room)]
     {:db (-> db
              (assoc-in [:vtt :create-room-name] "")
              (assoc-in [:vtt :room-snapshot] room)
              (assoc-in [:vtt :selected-scene-id] (::vtt/active-scene-id room)))
      :dispatch [:route (room-page-route room-id)]})))

(reg-event-fx
 ::delete-room
 (fn [{:keys [db]} [_ room-id]]
   {:dispatch [:set-loading true]
    :http {:method :delete
           :headers (authorization-headers db)
           :url (url-for-route routes/vtt-room-route :id room-id)
           :on-success [::delete-room-success room-id]
           :on-failure [::request-failure]}}))

(reg-event-db
 ::delete-room-success
 (fn [db [_ room-id _response]]
   (update-in db [:vtt :room-list]
              (fn [rooms]
                (->> (or rooms [])
                     (remove #(= room-id (:db/id %)))
                     vec)))))

(reg-event-fx
 ::room-command
 (fn [{:keys [db]} [_ room-id command & [after-success]]]
   {:dispatch [:set-loading true]
    :http {:method :post
           :headers (authorization-headers db)
           :url (url-for-route routes/vtt-room-commands-route :id room-id)
           :transit-params command
           :on-success [::room-command-success after-success]
           :on-failure [::request-failure]}}))

(reg-event-fx
 ::room-command-success
 (fn [{:keys [db]} [_ after-success response]]
   (let [room (response-room response)]
     (cond-> {:db (-> db
                      (assoc-in [:vtt :room-snapshot] room)
                      (assoc-in [:vtt :selected-scene-id] (::vtt/active-scene-id room)))}
       after-success (assoc :dispatch after-success)))))

(reg-event-fx
 ::create-asset-from-url
 (fn [{:keys [db]} [_ room-id scene-id]]
   (let [asset-url (s/trim (get-in db [:vtt :asset-url]))]
     (if (s/blank? asset-url)
       {:dispatch [:show-error-message "Map URL is required"]}
       {:dispatch [:set-loading true]
        :http {:method :post
               :headers (authorization-headers db)
               :url (url-for-route routes/vtt-assets-from-url-route)
               :transit-params {::vtt/url asset-url
                                ::vtt/kind :map}
               :on-success [::create-asset-success room-id scene-id]
               :on-failure [::request-failure]}}))))

(reg-event-fx
 ::upload-asset
 (fn [{:keys [db]} [_ room-id scene-id file]]
   (if-not file
     {:dispatch [:show-error-message "Choose a file to upload"]}
     {:dispatch [:set-loading true]
      :vtt/upload-file {:url (backend-url (routes/path-for routes/vtt-assets-upload-route))
                        :token (-> db :user-data :token)
                        :file file
                        :on-success [::create-asset-success room-id scene-id]
                        :on-failure [::request-failure]}})))

(reg-event-fx
 ::create-asset-success
 (fn [{:keys [db]} [_ room-id scene-id response]]
   (let [asset (response-asset response)]
     {:db (assoc-in db [:vtt :asset-url] "")
      :dispatch [::room-command room-id
                 {::vtt/command-type :set-scene-background
                  ::vtt/scene-id scene-id
                  ::vtt/asset-id (:db/id asset)}]})))

(reg-event-fx
 ::request-failure
 (fn [_ [_ response]]
   {:dispatch [:show-error-message
               (or (:message (response-body response))
                   "The VTT request failed.")]}))

(reg-event-fx
 ::close-room
 (fn [{:keys [db]} _]
   {:db (assoc db :vtt (vtt/default-client-state))
    :vtt/close-stream true}))
