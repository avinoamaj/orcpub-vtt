(ns orcpub.routes.vtt
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.string :as s]
            [datomic.api :as d]
            [buddy.auth.middleware :refer [authentication-request]]
            [buddy.auth.backends :as backends]
            [buddy.sign.jwt :as jwt]
            [environ.core :as environ]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.errors :as errors]
            [orcpub.entity.strict :as se]
            [orcpub.vtt :as vtt]
            [orcpub.vtt.broker :as broker]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp])
  (:import (java.nio.file Files StandardCopyOption)
           (java.time Instant)
           (java.util Base64 Date UUID)))

(def ^:private jwt-secret
  (environ/env :signature))

(def ^:private auth-backend
  (backends/jws {:secret jwt-secret}))

(defn- now []
  (Date/from (Instant/now)))

(defn- tempid
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn- query-token [request]
  (get (codec/form-decode (or (:query-string request) "")) "token"))

(defn request-identity
  [request]
  (or (:identity request)
      (when-let [token (query-token request)]
        (try
          (jwt/unsign token jwt-secret)
          (catch Throwable _
            nil)))
      (some-> request
              (authentication-request auth-backend)
              :identity)))

(defn- request-username [request]
  (:user (request-identity request)))

(defn- scene-name [db room-id]
  (str "Scene "
       (inc (count (d/q '[:find ?scene
                          :in $ ?room
                          :where [?room :orcpub.vtt/scenes ?scene]]
                        db
                        room-id)))))

(defn- room-membership-query [db room-id username]
  (d/q '[:find (pull ?m [*]) .
         :in $ ?room ?username
         :where [?room :orcpub.vtt/memberships ?m]
                [?m :orcpub.vtt/username ?username]]
       db
       room-id
       username))

(defn room-membership
  [db room-id username]
  (room-membership-query db room-id username))

(defn room-role
  [db room-id username]
  (:orcpub.vtt/role (room-membership db room-id username)))

(defn room-member?
  [db room-id username]
  (some? (room-membership db room-id username)))

(defn room-owner?
  [db room-id username]
  (= username
     (d/q '[:find ?owner .
            :in $ ?room
            :where [?room :orcpub.vtt/owner ?owner]]
          db
          room-id)))

(defn gm?
  [db room-id username]
  (or (room-owner? db room-id username)
      (= vtt/gm-role (room-role db room-id username))))

(defn- user-exists?
  [db username]
  (boolean
   (d/q '[:find ?u .
          :in $ ?username
          :where [?u :orcpub.user/username ?username]]
        db
        username)))

(defn- scene-room-id
  [db scene-id]
  (d/q '[:find ?room .
         :in $ ?scene
         :where [?room :orcpub.vtt/scenes ?scene]]
       db
       scene-id))

(defn- token-info
  [db token-id]
  (d/q '[:find ?room ?scene .
         :in $ ?token
         :where [?scene :orcpub.vtt/tokens ?token]
                [?room :orcpub.vtt/scenes ?scene]]
       db
       token-id))

(defn- asset-accessible?
  [db asset-id username]
  (or (= username
         (d/q '[:find ?owner .
                :in $ ?asset
                :where [?asset :orcpub.vtt/owner ?owner]]
              db
              asset-id))
      (boolean
       (d/q '[:find ?room .
              :in $ ?asset ?username
              :where [?scene :orcpub.vtt/background-asset ?asset]
                     [?room :orcpub.vtt/scenes ?scene]
                     [?room :orcpub.vtt/memberships ?m]
                     [?m :orcpub.vtt/username ?username]]
            db
            asset-id
            username))))

(def room-pull
  [:db/id
   ::vtt/name
   ::vtt/owner
   ::vtt/created
   {::vtt/active-scene [:db/id]}
   {::vtt/memberships [:db/id ::vtt/username ::vtt/role ::vtt/connected-state]}
   {::vtt/scenes
    [:db/id
     ::vtt/name
     ::vtt/player-name
     ::vtt/grid-enabled?
     ::vtt/grid-size
     ::vtt/grid-scale
     ::vtt/width
     ::vtt/height
     ::vtt/fog-enabled?
     {::vtt/background-asset
      [:db/id ::vtt/storage-type ::vtt/url ::vtt/file-path ::vtt/filename ::vtt/mime ::vtt/width ::vtt/height]}
     {::vtt/tokens
      [:db/id
       ::vtt/name
       ::vtt/label
       ::vtt/token-kind
       ::vtt/character-id
       ::vtt/monster-key
       ::vtt/x
       ::vtt/y
       ::vtt/width
       ::vtt/height
       ::vtt/hidden?
       ::vtt/hit-points
       ::vtt/max-hit-points
       ::vtt/initiative
       ::vtt/controllers
       ::vtt/conditions
       {::vtt/asset [:db/id ::vtt/storage-type ::vtt/url ::vtt/file-path ::vtt/filename ::vtt/mime]}]}]}
   {::vtt/combat-state
    [:db/id
     ::vtt/round
     ::vtt/notes
     {::vtt/current-turn-token [:db/id]}]}
   {::vtt/chat-messages
    [:db/id ::vtt/username ::vtt/message-kind ::vtt/message ::vtt/payload ::vtt/created]}])

(defn- sort-token
  [token]
  [(:db/id token)])

(defn- sort-scene
  [scene]
  [(:db/id scene)])

(defn- sort-message
  [message]
  [(or (::vtt/created message) (Date. 0))
   (:db/id message)])

(defn- token-display-name
  [db token]
  (if-let [character-id (::vtt/character-id token)]
    (if-let [summary (d/pull db [::se/summary] character-id)]
      (or (get-in summary [::se/summary ::char5e/character-name])
          (::vtt/name token))
      (::vtt/name token))
    (::vtt/name token)))

(defn room-snapshot
  [db room-id username]
  (let [connected-users (broker/room-members room-id)
        room (d/pull db room-pull room-id)
        active-scene-id (get-in room [::vtt/active-scene :db/id])]
    (-> room
        (assoc ::vtt/active-scene-id active-scene-id)
        (assoc ::vtt/viewer-role (room-role db room-id username))
        (update
         ::vtt/memberships
         (fn [memberships]
           (->> memberships
                (map (fn [membership]
                       (assoc membership
                              ::vtt/connected-state
                              (if (connected-users (::vtt/username membership))
                                :connected
                                :offline))))
                (sort-by (fn [membership]
                           [(get vtt/role-order (::vtt/role membership) 99)
                            (::vtt/username membership)]))
                vec)))
        (update
         ::vtt/scenes
         (fn [scenes]
           (->> scenes
                (sort-by sort-scene)
                (mapv (fn [scene]
                        (update
                         scene
                         ::vtt/tokens
                         (fn [tokens]
                           (->> tokens
                                (sort-by sort-token)
                                (mapv (fn [token]
                                        (assoc token
                                               ::vtt/name
                                               (token-display-name db token)))))))))))
        (update
         ::vtt/chat-messages
         (fn [messages]
           (->> messages
                (sort-by sort-message)
                vec)))))))

(defn- ok
  [body]
  {:status 200 :body body})

(defn- bad-request
  [message]
  {:status 400 :body {:message message}})

(defn- forbidden
  [message]
  {:status 403 :body {:message message}})

(defn- not-found
  [message]
  {:status 404 :body {:message message}})

(defn- unauthorized
  [message]
  {:status 401 :body {:message message}})

(defn- ensure-room-access
  [db room-id username]
  (cond
    (nil? username) (unauthorized "Unauthorized")
    (not (room-member? db room-id username)) (forbidden "You are not a member of this room")
    :else nil))

(defn list-rooms
  [{:keys [db identity]}]
  (let [username (:user identity)
        rooms (d/q '[:find ?room ?role
                     :in $ ?username
                     :where [?room :orcpub.vtt/memberships ?membership]
                            [?membership :orcpub.vtt/username ?username]
                            [?membership :orcpub.vtt/role ?role]]
                   db
                   username)]
    (ok
     (->> rooms
          (map (fn [[room-id role]]
                 (let [room (d/pull db [:db/id ::vtt/name ::vtt/owner ::vtt/created] room-id)]
                   (assoc room ::vtt/viewer-role role))))
          (sort-by (fn [room]
                     [(get vtt/role-order (::vtt/viewer-role room) 99)
                      (::vtt/name room)]))
          vec))))

(defn create-room
  [{:keys [conn identity]
    params :transit-params}]
  (let [username (:user identity)
        room-id (tempid "vtt-room")
        scene-id (tempid "vtt-scene")
        combat-id (tempid "vtt-combat")
        room-name (let [candidate (some-> (::vtt/name params) str s/trim)]
                    (if (s/blank? candidate)
                      "New VTT Room"
                      candidate))]
    (try
      (let [tx [{:db/id scene-id
                 ::vtt/name "Scene 1"
                 ::vtt/player-name "Scene 1"
                 ::vtt/grid-enabled? true
                 ::vtt/grid-size vtt/default-grid-size
                 ::vtt/grid-scale "5 ft"
                 ::vtt/width vtt/default-scene-width
                 ::vtt/height vtt/default-scene-height
                 ::vtt/fog-enabled? false}
                {:db/id combat-id
                 ::vtt/round 1
                 ::vtt/notes ""}
                {:db/id room-id
                 ::vtt/name room-name
                 ::vtt/owner username
                 ::vtt/created (now)
                 ::vtt/active-scene scene-id
                 ::vtt/combat-state combat-id
                 ::vtt/memberships [{::vtt/username username
                                     ::vtt/role vtt/gm-role
                                     ::vtt/connected-state :offline}]
                 ::vtt/scenes [scene-id]
                 ::vtt/chat-messages []}]
            result @(d/transact conn tx)
            created-room-id (d/resolve-tempid (d/db conn) (:tempids result) room-id)]
        (ok {:room (room-snapshot (d/db conn) created-room-id username)}))
      (catch Exception e
        (errors/log-error
         "ERROR:"
         (str "Failed to create VTT room: " (.getMessage e))
         {:username username
          :room-name room-name})
        {:status 500
         :body {:error :vtt-room-creation-failed
                :message "Unable to create the VTT room. Please try again or contact support."}}))))

(defn get-room
  [{:keys [db identity] {:keys [id]} :path-params}]
  (let [username (:user identity)]
    (or (ensure-room-access db id username)
        (ok {:room (room-snapshot db id username)}))))

(defn- asset-body
  [db asset-id]
  (d/pull db [:db/id
              ::vtt/owner
              ::vtt/kind
              ::vtt/storage-type
              ::vtt/url
              ::vtt/file-path
              ::vtt/filename
              ::vtt/mime
              ::vtt/width
              ::vtt/height
              ::vtt/created]
          asset-id))

(defn create-asset-from-url
  [{:keys [conn identity]
    params :transit-params}]
  (let [username (:user identity)
        url (some-> (::vtt/url params) str s/trim)
        kind (or (::vtt/kind params) :map)]
    (if (s/blank? url)
      (bad-request "Asset URL is required")
      (let [asset-id (tempid "vtt-asset")
            tx [{:db/id asset-id
                 ::vtt/owner username
                 ::vtt/kind kind
                 ::vtt/storage-type :url
                 ::vtt/url url
                 ::vtt/created (now)}]
            result @(d/transact conn tx)
            created-id (d/resolve-tempid (d/db conn) (:tempids result) asset-id)]
        (ok {:asset (asset-body (d/db conn) created-id)})))))

(defn- upload-root []
  (doto (io/file "data" "vtt-assets")
    (.mkdirs)))

(defn- extname [filename]
  (let [idx (.lastIndexOf ^String filename ".")]
    (when (pos? idx)
      (subs filename idx))))

(defn upload-asset
  [{:keys [conn identity multipart-params params transit-params]}]
  (let [username (:user identity)
        upload (or (get multipart-params "file")
                   (get params "file"))
        transit-upload transit-params]
    (if-not (or upload transit-upload)
      (bad-request "File upload is required")
      (let [filename (or (:filename upload) "upload.bin")
            filename (or filename (:filename transit-upload) "upload.bin")
            mime (or (:content-type upload) (:mime transit-upload) "application/octet-stream")
            asset-id (tempid "vtt-asset")
            stored-name (str (UUID/randomUUID) (or (extname filename) ""))
            destination (io/file (upload-root) stored-name)
            write-result (if upload
                           (Files/copy (.toPath ^java.io.File (:tempfile upload))
                                       (.toPath destination)
                                       (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
                           (let [data-url (or (:data-url transit-upload) "")
                                 encoded (second (re-find #"^data:[^;]+;base64,(.+)$" data-url))]
                             (if-not encoded
                               ::invalid-upload
                               (Files/write (.toPath destination)
                                            (.decode (Base64/getDecoder) ^String encoded)
                                            (make-array java.nio.file.OpenOption 0)))))]
        (if (= ::invalid-upload write-result)
          (bad-request "Invalid uploaded payload")
          (let [result @(d/transact conn [{:db/id asset-id
                                           ::vtt/owner username
                                           ::vtt/kind :map
                                           ::vtt/storage-type :upload
                                           ::vtt/file-path (.getPath destination)
                                           ::vtt/filename filename
                                           ::vtt/mime mime
                                           ::vtt/created (now)}])
                created-id (d/resolve-tempid (d/db conn) (:tempids result) asset-id)]
            (ok {:asset (asset-body (d/db conn) created-id)})))))))

(defn asset-content
  [{:keys [db] {:keys [id]} :path-params :as request}]
  (let [username (request-username request)]
    (cond
      (nil? username) (unauthorized "Unauthorized")
      (not (asset-accessible? db id username)) (forbidden "You cannot access this asset")
      :else
      (let [{:keys [::vtt/storage-type ::vtt/file-path ::vtt/url ::vtt/mime]} (asset-body db id)]
        (case storage-type
          :url (ring-resp/redirect url)
          :upload (-> (ring-resp/file-response file-path)
                      (assoc-in [:headers "Content-Type"] (or mime "application/octet-stream")))
          (not-found "Asset not found"))))))

(defn room-stream
  [{:keys [db] {:keys [id]} :path-params :as request}]
  (let [username (request-username request)]
    (cond
      (nil? username) (unauthorized "Unauthorized")
      (not (room-member? db id username)) (forbidden "You are not a member of this room")
      :else (broker/stream-response id username))))

(defn- ensure-gm
  [db room-id username]
  (when-not (gm? db room-id username)
    (forbidden "Only the GM can perform this action")))

(defn- ensure-token-control
  [db token-id username]
  (let [[room-id _scene-id] (token-info db token-id)
        token (d/pull db [:db/id ::vtt/controllers] token-id)]
    (cond
      (nil? room-id) (not-found "Token not found")
      (gm? db room-id username) nil
      (some #{username} (::vtt/controllers token)) nil
      :else (forbidden "You do not control this token"))))

(defn- replace-many-values!
  [conn entity-id attr current-values next-values]
  @(d/transact conn
               (concat
                (map (fn [value] [:db/retract entity-id attr value]) current-values)
                (when (seq next-values)
                  [{:db/id entity-id attr next-values}]))))

(defn- token-order
  [db room-id]
  (let [active-scene-id (get-in (d/pull db [::vtt/active-scene] room-id)
                                [::vtt/active-scene :db/id])
        active-scene (some #(when (= active-scene-id (:db/id %)) %)
                           (get-in (d/pull db [{::vtt/scenes [:db/id {::vtt/tokens [:db/id ::vtt/initiative]}]}]
                                            room-id)
                                   [::vtt/scenes]))]
    (->> (::vtt/tokens active-scene)
         (sort-by (fn [token]
                    [(- (or (::vtt/initiative token) 0))
                     (:db/id token)]))
         vec)))

(defn- advance-turn!
  [db conn room-id]
  (let [combat-state-id (get-in (d/pull db [::vtt/combat-state] room-id) [::vtt/combat-state :db/id])
        combat-state (d/pull db [:db/id ::vtt/round {::vtt/current-turn-token [:db/id]}] combat-state-id)
        ordered-tokens (token-order db room-id)
        current-id (get-in combat-state [::vtt/current-turn-token :db/id])
        token-ids (mapv :db/id ordered-tokens)]
    (when (seq token-ids)
      (let [current-index (.indexOf token-ids current-id)
            next-index (if (neg? current-index)
                         0
                         (mod (inc current-index) (count token-ids)))
            next-token-id (nth token-ids next-index)
            wrapped? (and (not (neg? current-index))
                          (= 0 next-index))
            next-round (if wrapped?
                         (inc (or (::vtt/round combat-state) 1))
                         (or (::vtt/round combat-state) 1))]
        @(d/transact conn [{:db/id combat-state-id
                            ::vtt/current-turn-token next-token-id
                            ::vtt/round next-round}])))))

(defmulti execute-command
  (fn [_db _conn _room-id _username command]
    (::vtt/command-type command)))

(defmethod execute-command :create-scene
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [scene-id (tempid "vtt-scene")
            name (let [candidate (some-> (::vtt/name command) str s/trim)]
                   (if (s/blank? candidate)
                     (scene-name db room-id)
                     candidate))]
        @(d/transact conn [{:db/id scene-id
                            ::vtt/name name
                            ::vtt/player-name name
                            ::vtt/grid-enabled? true
                            ::vtt/grid-size vtt/default-grid-size
                            ::vtt/grid-scale "5 ft"
                            ::vtt/width vtt/default-scene-width
                            ::vtt/height vtt/default-scene-height
                            ::vtt/fog-enabled? false}
                           {:db/id room-id
                            ::vtt/scenes [scene-id]
                            ::vtt/active-scene scene-id}])
        nil)))

(defmethod execute-command :set-active-scene
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [scene-id (::vtt/scene-id command)]
        (if (not= room-id (scene-room-id db scene-id))
          (not-found "Scene not found")
          (do
            @(d/transact conn [{:db/id room-id
                                ::vtt/active-scene scene-id}])
            nil)))))

(defmethod execute-command :add-member
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [member-username (some-> (::vtt/username command) str s/trim)]
        (cond
          (s/blank? member-username) (bad-request "Username is required")
          (not (user-exists? db member-username)) (not-found "User not found")
          (room-member? db room-id member-username) (bad-request "User is already in the room")
          :else
          (do
            @(d/transact conn [{:db/id room-id
                                ::vtt/memberships [{::vtt/username member-username
                                                    ::vtt/role vtt/player-role
                                                    ::vtt/connected-state :offline}]}])
            nil)))))

(defmethod execute-command :spawn-token
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [scene-id (::vtt/scene-id command)
            token (::vtt/token command)
            token-id (tempid "vtt-token")
            name (let [candidate (some-> (::vtt/name token) str s/trim)]
                   (if (s/blank? candidate) "Token" candidate))
            cleaned-token (merge
                           {::vtt/name name
                            ::vtt/label (or (::vtt/label token) name)
                            ::vtt/token-kind (or (::vtt/token-kind token) :npc)
                            ::vtt/x (or (::vtt/x token) 24)
                            ::vtt/y (or (::vtt/y token) 24)
                            ::vtt/width (or (::vtt/width token) vtt/default-token-size)
                            ::vtt/height (or (::vtt/height token) vtt/default-token-size)
                            ::vtt/hidden? (boolean (::vtt/hidden? token))
                            ::vtt/hit-points (or (::vtt/hit-points token) 0)
                            ::vtt/max-hit-points (or (::vtt/max-hit-points token) 0)
                            ::vtt/initiative (or (::vtt/initiative token) 0)
                            ::vtt/controllers (vec (or (::vtt/controllers token) []))
                            ::vtt/conditions (vec (or (::vtt/conditions token) []))}
                           (select-keys token [::vtt/character-id ::vtt/monster-key ::vtt/asset]))]
        (if (not= room-id (scene-room-id db scene-id))
          (not-found "Scene not found")
          (do
            @(d/transact conn [{:db/id token-id
                                ::vtt/name (::vtt/name cleaned-token)
                                ::vtt/label (::vtt/label cleaned-token)
                                ::vtt/token-kind (::vtt/token-kind cleaned-token)
                                ::vtt/character-id (::vtt/character-id cleaned-token)
                                ::vtt/monster-key (::vtt/monster-key cleaned-token)
                                ::vtt/x (::vtt/x cleaned-token)
                                ::vtt/y (::vtt/y cleaned-token)
                                ::vtt/width (::vtt/width cleaned-token)
                                ::vtt/height (::vtt/height cleaned-token)
                                ::vtt/hidden? (::vtt/hidden? cleaned-token)
                                ::vtt/hit-points (::vtt/hit-points cleaned-token)
                                ::vtt/max-hit-points (::vtt/max-hit-points cleaned-token)
                                ::vtt/initiative (::vtt/initiative cleaned-token)
                                ::vtt/controllers (::vtt/controllers cleaned-token)
                                ::vtt/conditions (::vtt/conditions cleaned-token)
                                ::vtt/asset (::vtt/asset cleaned-token)}
                               {:db/id scene-id
                                ::vtt/tokens [token-id]}])
            nil)))))

(defmethod execute-command :move-token
  [db conn room-id username command]
  (let [token-id (::vtt/token-id command)]
    (or (ensure-token-control db token-id username)
        (let [[token-room-id _scene-id] (token-info db token-id)]
          (if (not= room-id token-room-id)
            (not-found "Token not found")
            (do
              @(d/transact conn [{:db/id token-id
                                  ::vtt/x (int (or (::vtt/x command) 0))
                                  ::vtt/y (int (or (::vtt/y command) 0))}])
              nil))))))

(defmethod execute-command :set-token-initiative
  [db conn room-id username command]
  (let [token-id (::vtt/token-id command)]
    (or (ensure-token-control db token-id username)
        (let [[token-room-id _scene-id] (token-info db token-id)]
          (if (not= room-id token-room-id)
            (not-found "Token not found")
            (do
              @(d/transact conn [{:db/id token-id
                                  ::vtt/initiative (int (or (::vtt/initiative command) 0))}])
              nil))))))

(defmethod execute-command :apply-damage
  [db conn room-id username command]
  (let [token-id (::vtt/token-id command)]
    (or (ensure-token-control db token-id username)
        (let [[token-room-id _scene-id] (token-info db token-id)
              token (d/pull db [:db/id ::vtt/hit-points] token-id)
              amount (int (or (::vtt/amount command) 0))
              next-hp (max 0 (- (or (::vtt/hit-points token) 0) amount))]
          (if (not= room-id token-room-id)
            (not-found "Token not found")
            (do
              @(d/transact conn [{:db/id token-id
                                  ::vtt/hit-points next-hp}])
              nil))))))

(defmethod execute-command :set-token-conditions
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [token-id (::vtt/token-id command)
            [token-room-id _scene-id] (token-info db token-id)
            token (d/pull db [:db/id ::vtt/conditions] token-id)
            next-conditions (vec (distinct (or (::vtt/conditions command) [])))]
        (if (not= room-id token-room-id)
          (not-found "Token not found")
          (do
            (replace-many-values! conn token-id ::vtt/conditions (::vtt/conditions token) next-conditions)
            nil)))))

(defmethod execute-command :advance-turn
  [db conn room-id username _command]
  (or (ensure-gm db room-id username)
      (do
        (advance-turn! db conn room-id)
        nil)))

(defmethod execute-command :send-chat
  [db conn room-id username command]
  (let [message (some-> (::vtt/message command) str s/trim)]
    (if (s/blank? message)
      (bad-request "Message is required")
      (do
        @(d/transact conn [{:db/id room-id
                            ::vtt/chat-messages [{::vtt/username username
                                                  ::vtt/message-kind (or (::vtt/message-kind command) :text)
                                                  ::vtt/message message
                                                  ::vtt/payload (or (::vtt/payload command) "")
                                                  ::vtt/created (now)}]}])
        nil))))

(defmethod execute-command :set-scene-background
  [db conn room-id username command]
  (or (ensure-gm db room-id username)
      (let [scene-id (::vtt/scene-id command)
            asset-id (::vtt/asset-id command)]
        (cond
          (not= room-id (scene-room-id db scene-id)) (not-found "Scene not found")
          (not (asset-accessible? db asset-id username)) (forbidden "You cannot use this asset")
          :else
          (do
            @(d/transact conn [{:db/id scene-id
                                ::vtt/background-asset asset-id}])
            nil)))))

(defmethod execute-command :default
  [_db _conn _room-id _username command]
  (bad-request (str "Unsupported command: " (::vtt/command-type command))))

(defn command
  [{:keys [db conn identity]
    {:keys [id]} :path-params
    command :transit-params}]
  (let [username (:user identity)]
    (or (ensure-room-access db id username)
        (if-not (spec/valid? ::vtt/command command)
          (bad-request "Invalid VTT command")
          (let [result (execute-command db conn id username command)]
            (if result
              result
              (let [snapshot (room-snapshot (d/db conn) id username)]
                (broker/publish! id {:type :room-updated
                                     :room-id id
                                     :command (::vtt/command-type command)
                                     :at (.getTime (now))})
                (ok {:room snapshot}))))))))
