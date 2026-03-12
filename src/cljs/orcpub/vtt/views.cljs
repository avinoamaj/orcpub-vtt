(ns orcpub.vtt.views
  (:require [clojure.string :as s]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.encounters :as encounters5e]
            [orcpub.dnd.e5.monsters :as monsters5e]
            [orcpub.dnd.e5.party :as party5e]
            [orcpub.dnd.e5.spell-subs]
            [orcpub.dnd.e5.subs]
            [orcpub.dnd.e5.views :as shared-views]
            [orcpub.dnd.e5.event-utils :as event-utils]
            [orcpub.entity.strict :as se]
            [orcpub.route-map :as routes]
            [orcpub.vtt :as vtt]
            [orcpub.vtt.events :as events]
            [orcpub.vtt.subs :as subs]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]))

(defn- route-match
  [route & args]
  (routes/match-route (apply routes/path-for route args)))

(defn- token-turn-id [room]
  (get-in room [::vtt/combat-state ::vtt/current-turn-token :db/id]))

(defn- initiative-order
  [tokens]
  (sort-by (fn [token]
             [(- (or (::vtt/initiative token) 0))
              (:db/id token)])
           tokens))

(defn- can-control-token?
  [username viewer-role token]
  (or (= viewer-role vtt/gm-role)
      (some #{username} (::vtt/controllers token))))

(defn- asset-url
  [token asset]
  (when asset
    (if (= :url (::vtt/storage-type asset))
      (::vtt/url asset)
      (str (event-utils/backend-url (routes/path-for routes/vtt-asset-content-route :id (:db/id asset)))
           "?token="
           (js/encodeURIComponent token)))))

(defn- board-background-style
  [scene token]
  (let [grid-size (or (::vtt/grid-size scene) vtt/default-grid-size)
        background-asset (::vtt/background-asset scene)
        background-url (asset-url token background-asset)
        grid-color "rgba(255,255,255,0.08)"
        base-style {:position "relative"
                    :width (str (::vtt/width scene) "px")
                    :height (str (::vtt/height scene) "px")
                    :overflow "hidden"
                    :border "1px solid rgba(255,255,255,0.2)"
                    :border-radius "8px"
                    :background-color "#1f2633"}]
    (cond-> base-style
      background-url
      (assoc :background-image (str "url('" background-url "')")
             :background-size "cover"
             :background-position "center")

      (::vtt/grid-enabled? scene)
      (assoc :box-shadow (str "inset 0 0 0 9999px rgba(0,0,0,0.18)")
             :background-blend-mode "multiply"
             :background-image
             (str
              (when background-url (str "url('" background-url "'),"))
              "linear-gradient(to right, " grid-color " 1px, transparent 1px),"
              "linear-gradient(to bottom, " grid-color " 1px, transparent 1px)")
             :background-size
             (str
              (when background-url "cover,")
              grid-size "px " grid-size "px,"
              grid-size "px " grid-size "px")))))

(defn- spawn-party!
  [room-id scene-id party scene-token-count]
  (doseq [[index character] (map-indexed vector (::party5e/character-ids party))]
    (dispatch
     [::events/room-command room-id
      {::vtt/command-type :spawn-token
       ::vtt/scene-id scene-id
       ::vtt/token {::vtt/name (or (::char5e/character-name character)
                                   (str "Character " (:db/id character)))
                    ::vtt/label (or (::char5e/character-name character) "PC")
                    ::vtt/token-kind :pc
                    ::vtt/character-id (:db/id character)
                    ::vtt/controllers (cond-> []
                                        (::se/owner character) (conj (::se/owner character)))
                    ::vtt/x (+ 24 (* (mod (+ scene-token-count index) 8) 54))
                    ::vtt/y (+ 24 (* (quot (+ scene-token-count index) 8) 54))
                    ::vtt/width vtt/default-token-size
                    ::vtt/height vtt/default-token-size
                    ::vtt/max-hit-points 0
                    ::vtt/hit-points 0}}])))

(defn- spawn-encounter!
  [room-id scene-id encounter character-map monster-map scene-token-count]
  (doseq [[index creature] (map-indexed vector (:creatures encounter))]
    (case (:type creature)
      :character
      (when-let [character (character-map (get-in creature [:creature :character]))]
        (dispatch
         [::events/room-command room-id
          {::vtt/command-type :spawn-token
           ::vtt/scene-id scene-id
           ::vtt/token {::vtt/name (or (::char5e/character-name character)
                                       (str "Character " (:db/id character)))
                        ::vtt/label (or (::char5e/character-name character) "NPC")
                        ::vtt/token-kind :npc
                        ::vtt/character-id (:db/id character)
                        ::vtt/x (+ 24 (* (mod (+ scene-token-count index) 8) 54))
                        ::vtt/y (+ 24 (* (quot (+ scene-token-count index) 8) 54))
                        ::vtt/width vtt/default-token-size
                        ::vtt/height vtt/default-token-size}}]))
      :monster
      (let [{:keys [monster num]} (:creature creature)
            monster-data (monster-map monster)
            copies (or num 1)]
        (doseq [copy-index (range copies)]
          (dispatch
           [::events/room-command room-id
            {::vtt/command-type :spawn-token
             ::vtt/scene-id scene-id
             ::vtt/token {::vtt/name (:name monster-data)
                          ::vtt/label (:name monster-data)
                          ::vtt/token-kind :monster
                          ::vtt/monster-key monster
                          ::vtt/x (+ 24 (* (mod (+ scene-token-count index copy-index) 8) 54))
                          ::vtt/y (+ 24 (* (quot (+ scene-token-count index copy-index) 8) 54))
                          ::vtt/width vtt/default-token-size
                          ::vtt/height vtt/default-token-size
                          ::vtt/max-hit-points (get-in monster-data [:hit-points :mean] 0)
                          ::vtt/hit-points (get-in monster-data [:hit-points :mean] 0)}}]))))))

(defn vtt-room-list-page []
  (r/with-let [_ (dispatch [::events/load-rooms])]
    (let [rooms @(subscribe [::subs/room-list])
          room-name @(subscribe [::subs/create-room-name])]
      [shared-views/content-page
       "VTT Rooms"
       [{:title "Refresh"
         :icon "redo"
         :on-click #(dispatch [::events/load-rooms])}]
       [:div.p-20.main-text-color
        [:div.bg-lighter.p-20.b-rad-5.m-b-20
         [:div.f-s-24.f-w-b.m-b-10 "Create Room"]
         [:div.flex.flex-wrap.align-items-end
          [:div.flex-grow-1.m-r-10
           [:div.f-s-14.m-b-5 "Room name"]
           [:input.input.w-100-p
            {:value room-name
             :placeholder "New VTT Room"
             :on-change #(dispatch [::events/set-create-room-name (.. % -target -value)])}]]
          [:button.form-button
           {:on-click #(dispatch [::events/create-room])}
           "Create"]]]
        [:div.f-s-24.f-w-b.m-b-10 "Your Rooms"]
        (if (seq rooms)
          [:div.item-list
           (for [{:keys [db/id] :as room} rooms]
             ^{:key id}
             [:div.item-list-item.p-20.flex.justify-cont-s-b.align-items-c
              [:div
               [:div.f-s-24.f-w-b (::vtt/name room)]
               [:div.f-s-14.opacity-7 (str "Role: " (name (::vtt/viewer-role room)))]
               [:div.f-s-14.opacity-7 (str "Owner: " (::vtt/owner room))]]
              [:div.flex
               [:button.form-button.m-r-10
                {:on-click #(dispatch [:route (route-match routes/vtt-room-page-route :id id)])}
                "Open"]
               (when (= (::vtt/viewer-role room) vtt/gm-role)
                 [:button.form-button
                  {:on-click #(when (js/confirm (str "Delete room \"" (::vtt/name room) "\"?"))
                                (dispatch [::events/delete-room id]))}
                  "Delete"])]]])]
          [:div.bg-lighter.p-20.b-rad-5 "No VTT rooms yet."])]])))

(defn- scene-sidebar [room active-scene can-gm?]
  (let [room-id (:db/id room)
        scenes (::vtt/scenes room)]
    [:div
     [:div.f-s-20.f-w-b.m-b-10 "Scenes"]
     (for [scene scenes]
       ^{:key (:db/id scene)}
       [:div.pointer.p-10.m-b-5.b-rad-5
        {:class (when (= (:db/id scene) (:db/id active-scene)) "bg-lighter")
         :on-click #(if can-gm?
                      (dispatch [::events/room-command room-id
                                 {::vtt/command-type :set-active-scene
                                  ::vtt/scene-id (:db/id scene)}])
                      (dispatch [::events/select-scene (:db/id scene)]))}
        [:div.f-w-b (::vtt/name scene)]
        [:div.f-s-12.opacity-7 (::vtt/player-name scene)]])
     (when can-gm?
       [:button.form-button.m-t-10
       {:on-click #(dispatch [::events/room-command room-id
                               {::vtt/command-type :create-scene
                                ::vtt/name (str "Scene " (inc (count scenes)))}])}
        "Add Scene"])]))

(defn- import-panel [room active-scene can-gm?]
  (let [parties @(subscribe [::party5e/parties true])
        character-map @(subscribe [::char5e/summary-map true])
        encounters @(subscribe [::encounters5e/encounters])
        monster-map @(subscribe [::monsters5e/monster-map])
        scene-token-count (count (::vtt/tokens active-scene))
        room-id (:db/id room)
        scene-id (:db/id active-scene)]
    (when can-gm?
      [:div
       [:div.f-s-20.f-w-b.m-b-10 "Import"]
       [:div.m-b-10
        [:div.f-s-14.m-b-5 "Parties"]
        (for [party parties]
          ^{:key (:db/id party)}
          [:button.form-button.m-r-5.m-b-5
           {:on-click #(spawn-party! room-id scene-id party scene-token-count)}
           (::party5e/name party)])]
       [:div
        [:div.f-s-14.m-b-5 "Encounters"]
        (for [encounter encounters]
          ^{:key (:key encounter)}
          [:button.form-button.m-r-5.m-b-5
           {:on-click #(spawn-encounter! room-id scene-id encounter character-map monster-map scene-token-count)}
           (:name encounter)])]])))

(defn- map-panel [room active-scene can-gm?]
  (let [asset-url-text @(subscribe [::subs/asset-url])
        room-id (:db/id room)
        scene-id (:db/id active-scene)]
    (when can-gm?
      [:div.m-t-20
       [:div.f-s-20.f-w-b.m-b-10 "Map"]
       [:input.input.w-100-p.m-b-10
        {:value asset-url-text
         :placeholder "https://example.com/map.png"
         :on-change #(dispatch [::events/set-asset-url (.. % -target -value)])}]
       [:div.flex.flex-wrap
        [:button.form-button.m-r-5
         {:on-click #(dispatch [::events/create-asset-from-url room-id scene-id])}
         "Use URL"]
        [:label.form-button.pointer
         [:span "Upload"]
         [:input.hidden
          {:type "file"
           :accept "image/*"
           :on-change #(dispatch [::events/upload-asset room-id scene-id (aget (.. % -target -files) 0)])}]]]])))

(defn- board [room active-scene selected-token username viewer-role auth-token]
  (let [room-id (:db/id room)
        current-turn-id (token-turn-id room)]
    [:div
     [:div.f-s-20.f-w-b.m-b-10 "Scene Board"]
     [:div
      {:style (board-background-style active-scene auth-token)
       :on-click (fn [event]
                   (when (and selected-token
                              (can-control-token? username viewer-role selected-token))
                     (let [rect (.getBoundingClientRect (.-currentTarget event))
                           x (int (- (.-clientX event) (.-left rect) (/ (::vtt/width selected-token) 2)))
                           y (int (- (.-clientY event) (.-top rect) (/ (::vtt/height selected-token) 2)))]
                       (dispatch [::events/room-command room-id
                                  {::vtt/command-type :move-token
                                   ::vtt/token-id (:db/id selected-token)
                                   ::vtt/x (max 0 x)
                                   ::vtt/y (max 0 y)}]))))}
      (for [scene-token (::vtt/tokens active-scene)]
        ^{:key (:db/id scene-token)}
        [:div.pointer.flex.align-items-c.justify-cont-c
         {:style {:position "absolute"
                  :left (str (::vtt/x scene-token) "px")
                  :top (str (::vtt/y scene-token) "px")
                  :width (str (::vtt/width scene-token) "px")
                  :height (str (::vtt/height scene-token) "px")
                  :border-radius "999px"
                  :border (str "2px solid "
                               (cond
                                 (= (:db/id selected-token) (:db/id scene-token)) "#f2c96d"
                                 (= current-turn-id (:db/id scene-token)) "#5fe3a1"
                                 :else "rgba(255,255,255,0.5)"))
                  :background "rgba(8,12,18,0.75)"
                  :color "white"
                  :font-size "11px"
                  :text-align "center"
                  :padding "4px"
                  :box-shadow "0 6px 20px rgba(0,0,0,0.35)"}
          :on-click (fn [event]
                      (.stopPropagation event)
                      (dispatch [::events/select-token (:db/id scene-token)]))}
         [:div
          [:div.f-w-b (::vtt/label scene-token)]
          [:div.f-s-10
           (str (::vtt/hit-points scene-token) "/" (::vtt/max-hit-points scene-token))]]])]]))

(defn- token-editor [room active-scene selected-token username viewer-role]
  (r/with-let [damage* (r/atom "")
               initiative* (r/atom (str (or (::vtt/initiative selected-token) 0)))
               conditions* (r/atom (s/join ", " (map name (::vtt/conditions selected-token))))]
    (let [room-id (:db/id room)
          can-control? (and selected-token (can-control-token? username viewer-role selected-token))
          can-gm? (= viewer-role vtt/gm-role)]
      [:div
       [:div.f-s-20.f-w-b.m-b-10 "Selected Token"]
       (if selected-token
         [:div.bg-lighter.p-15.b-rad-5
          [:div.f-s-18.f-w-b.m-b-5 (::vtt/name selected-token)]
          [:div.f-s-12.opacity-7.m-b-10 (name (::vtt/token-kind selected-token))]
          [:div.f-s-14.m-b-5 "Initiative"]
          [:input.input.w-100-p.m-b-10
           {:value @initiative*
            :disabled (not can-control?)
            :on-change #(reset! initiative* (.. % -target -value))}]
          [:button.form-button.m-b-10
           {:disabled (not can-control?)
            :on-click #(dispatch [::events/room-command room-id
                                  {::vtt/command-type :set-token-initiative
                                   ::vtt/token-id (:db/id selected-token)
                                   ::vtt/initiative (or (js/parseInt @initiative* 10) 0)}])}
           "Save Initiative"]
          [:div.f-s-14.m-b-5 "Damage"]
          [:div.flex.m-b-10
           [:input.input.flex-grow-1.m-r-5
            {:value @damage*
             :disabled (not can-control?)
             :on-change #(reset! damage* (.. % -target -value))}]
           [:button.form-button
            {:disabled (not can-control?)
             :on-click #(do
                          (dispatch [::events/room-command room-id
                                     {::vtt/command-type :apply-damage
                                      ::vtt/token-id (:db/id selected-token)
                                      ::vtt/amount (or (js/parseInt @damage* 10) 0)}])
                          (reset! damage* ""))}
            "Apply"]]
          [:div.f-s-14.m-b-5 "Conditions"]
          [:input.input.w-100-p.m-b-10
           {:value @conditions*
            :disabled (not can-gm?)
            :placeholder "poisoned, prone"
            :on-change #(reset! conditions* (.. % -target -value))}]
          [:button.form-button
           {:disabled (not can-gm?)
            :on-click #(dispatch [::events/room-command room-id
                                  {::vtt/command-type :set-token-conditions
                                   ::vtt/token-id (:db/id selected-token)
                                   ::vtt/conditions (->> (s/split @conditions* #",")
                                                         (map s/trim)
                                                         (remove s/blank?)
                                                         (map keyword)
                                                         vec)}])}
           "Save Conditions"]]
         [:div.bg-lighter.p-15.b-rad-5 "Select a token on the board."])])))

(defn- combat-panel [room active-scene viewer-role]
  (let [room-id (:db/id room)
        current-turn-id (token-turn-id room)]
    [:div
     [:div.flex.justify-cont-s-b.align-items-c.m-b-10
      [:div.f-s-20.f-w-b "Combat"]
      (when (= viewer-role vtt/gm-role)
        [:button.form-button
         {:on-click #(dispatch [::events/room-command room-id
                                {::vtt/command-type :advance-turn}])}
         "Advance Turn"])]
     [:div.bg-lighter.p-15.b-rad-5
      [:div.m-b-5 (str "Round " (get-in room [::vtt/combat-state ::vtt/round] 1))]
      (for [token (initiative-order (::vtt/tokens active-scene))]
        ^{:key (:db/id token)}
        [:div.flex.justify-cont-s-b.p-5
         {:class (when (= current-turn-id (:db/id token)) "orange")}
         [:span (::vtt/name token)]
         [:span (or (::vtt/initiative token) 0)]])]]))

(defn- chat-panel [room]
  (let [room-id (:db/id room)
        chat-draft @(subscribe [::subs/chat-draft])]
    [:div.m-t-20
     [:div.f-s-20.f-w-b.m-b-10 "Chat"]
     [:div.bg-lighter.p-15.b-rad-5.m-b-10
      {:style {:max-height "240px"
               :overflow "auto"}}
      (for [message (::vtt/chat-messages room)]
        ^{:key (:db/id message)}
        [:div.m-b-10
         [:div.f-w-b (::vtt/username message)]
         [:div (::vtt/message message)]])]
     [:div.flex
      [:input.input.flex-grow-1.m-r-5
       {:value chat-draft
        :placeholder "Type a message"
        :on-change #(dispatch [::events/set-chat-draft (.. % -target -value)])}]
      [:button.form-button
       {:on-click #(when-not (s/blank? chat-draft)
                     (dispatch [::events/room-command room-id
                                {::vtt/command-type :send-chat
                                 ::vtt/message chat-draft}
                                [::events/set-chat-draft ""]]))}
       "Send"]]]))

(defn vtt-room-page [{:keys [id]}]
  (r/with-let [room-id (js/parseInt id 10)
               _ (dispatch [::events/load-room room-id true])]
    (let [room @(subscribe [::subs/room-snapshot])
          active-scene @(subscribe [::subs/active-scene])
          selected-token @(subscribe [::subs/selected-token])
          viewer-role @(subscribe [::subs/viewer-role])
          stream-status @(subscribe [::subs/stream-status])
          member-username @(subscribe [::subs/member-username])
          auth-token (:token @(subscribe [:user-data]))
          username @(subscribe [:username])]
      [shared-views/content-page
       "VTT Room"
       [{:title "Back to Rooms"
         :icon "arrow-left"
         :on-click #(dispatch [:route (route-match routes/vtt-rooms-page-route)])}]
       (if (and room active-scene)
         [:div.p-20.main-text-color
          [:div.flex.justify-cont-s-b.align-items-c.flex-wrap.m-b-20
           [:div
            [:div.f-s-30.f-w-b (::vtt/name room)]
            [:div.f-s-14.opacity-7
             (str "Role: " (name viewer-role) " | Stream: " (name stream-status))]]
           [:div.f-s-14.opacity-7
           (str "Owner: " (::vtt/owner room))]]
          [:div.flex.flex-wrap
           [:div.m-r-20
            {:style {:width "250px"}}
             [scene-sidebar room active-scene (= viewer-role vtt/gm-role)]
             (when (= viewer-role vtt/gm-role)
               [:div.m-t-20
               [:div.f-s-20.f-w-b.m-b-10 "Members"]
               [:div.bg-lighter.p-15.b-rad-5.m-b-10
               (for [membership (::vtt/memberships room)]
                  ^{:key (:db/id membership)}
                  [:div.flex.justify-cont-s-b.m-b-5
                   [:span (::vtt/username membership)]
                   [:span.opacity-7 (name (::vtt/role membership))]])]
               [:input.input.w-100-p.m-b-10
                {:value member-username
                 :placeholder "Username"
                 :on-change #(dispatch [::events/set-member-username (.. % -target -value)])}]
               [:button.form-button
                {:on-click #(dispatch [::events/room-command room-id
                                       {::vtt/command-type :add-member
                                        ::vtt/username member-username}
                                       [::events/set-member-username ""]])}
                 "Add Player"]])
             [import-panel room active-scene (= viewer-role vtt/gm-role)]
             [map-panel room active-scene (= viewer-role vtt/gm-role)]]
           [:div.flex-grow-1
            [board room active-scene selected-token username viewer-role auth-token]
             [chat-panel room]]
           [:div.m-l-20
            {:style {:width "300px"}}
            [combat-panel room active-scene viewer-role]
            ^{:key (or (:db/id selected-token) "none")}
            [token-editor room active-scene selected-token username viewer-role]]]]
         [:div.p-20.main-text-color "Loading VTT room..."])])
    (finally
      (dispatch [::events/close-room]))))
