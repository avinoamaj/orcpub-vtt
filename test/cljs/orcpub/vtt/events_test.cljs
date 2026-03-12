(ns orcpub.vtt.events-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [orcpub.vtt :as vtt]
            [orcpub.vtt.events :as events]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(defn reset-db!
  []
  (reset! app-db {:vtt (vtt/default-client-state)
                  :user-data {:token "test-token"}}))

(use-fixtures :each {:before reset-db!})

(deftest load-rooms-success-normalizes-nested-response-body
  (rf/dispatch-sync
   [::events/load-rooms-success
    {:body {:body [{:db/id 1 ::vtt/name "Arena"}
                   {:db/id 2 ::vtt/name "Dungeon"}]}}])
  (is (= ["Arena" "Dungeon"]
         (mapv ::vtt/name (get-in @app-db [:vtt :room-list])))))

(deftest load-room-success-stores-room-snapshot-and-selection
  (let [room {:db/id 7
              ::vtt/name "Arena"
              ::vtt/active-scene-id 11
              ::vtt/scenes [{:db/id 11 ::vtt/name "Scene 1"}]}]
    (rf/dispatch-sync
     [::events/load-room-success
      7
      false
      {:body {:body {:room room}}}])
    (is (= room (get-in @app-db [:vtt :room-snapshot])))
    (is (= 11 (get-in @app-db [:vtt :selected-scene-id])))
    (is (= [] (get-in @app-db [:vtt :selected-token-ids])))))

(deftest stream-event-ignores-other-rooms
  (reset! app-db {:vtt (assoc (vtt/default-client-state)
                              :room-snapshot {:db/id 7}
                              :stream-status :open)})
  (rf/dispatch-sync
   [::events/stream-event {:type :room-updated :room-id 99}])
  (is (= {:db/id 7} (get-in @app-db [:vtt :room-snapshot])))
  (is (= :open (get-in @app-db [:vtt :stream-status]))))

(deftest close-room-resets-vtt-client-state
  (reset! app-db {:vtt {:room-snapshot {:db/id 7}
                        :room-list [{:db/id 7}]
                        :stream-status :open
                        :selected-scene-id 11
                        :selected-token-ids [12]
                        :asset-url "https://example.com/map.png"
                        :chat-draft "hello"
                        :member-username "player-one"
                        :create-room-name "Arena"}
                  :user-data {:token "test-token"}})
  (rf/dispatch-sync [::events/close-room])
  (is (= (vtt/default-client-state) (:vtt @app-db))))
