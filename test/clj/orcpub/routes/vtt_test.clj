(ns orcpub.routes.vtt-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [datomock.core :as dm]
            [orcpub.db.schema :as schema]
            [orcpub.routes.vtt :as vtt-routes]
            [orcpub.vtt :as vtt])
  (:import [java.util UUID]))

(defmacro with-conn [conn-binding & body]
  `(let [uri# (str "datomic:mem:orcpub-vtt-test-" (UUID/randomUUID))
         ~conn-binding (do
                         (d/create-database uri#)
                         (d/connect uri#))]
     (try
       ~@body
       (finally
         (d/delete-database uri#)))))

(defn- seed-users!
  [conn usernames]
  @(d/transact conn
               (mapv (fn [username]
                       {:orcpub.user/username username
                        :orcpub.user/email (str username "@test.dev")})
                     usernames)))

(defn- create-room!
  [conn username room-name]
  (-> (vtt-routes/create-room {:conn conn
                               :identity {:user username}
                               :transit-params {::vtt/name room-name}})
      :body
      :room))

(defn- snapshot-for
  [conn username room-id]
  (-> (vtt-routes/get-room {:db (d/db conn)
                            :identity {:user username}
                            :path-params {:id room-id}})
      :body
      :room))

(defn- command!
  [conn username room-id payload]
  (vtt-routes/command {:db (d/db conn)
                       :conn conn
                       :identity {:user username}
                       :path-params {:id room-id}
                       :transit-params payload}))

(defn- active-scene-id
  [room]
  (::vtt/active-scene-id room))

(defn- active-scene
  [room]
  (some #(when (= (active-scene-id room) (:db/id %)) %)
        (::vtt/scenes room)))

(defn- token-by-name
  [room token-name]
  (some #(when (= token-name (::vtt/name %)) %)
        (::vtt/tokens (active-scene room))))

(deftest create-room-initializes-shared-vtt-state
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (seed-users! mocked-conn ["gm-user"])
      (let [room (create-room! mocked-conn "gm-user" "Arena")
            scene (first (::vtt/scenes room))
            membership (first (::vtt/memberships room))]
        (is (= "Arena" (::vtt/name room)))
        (is (= "gm-user" (::vtt/owner room)))
        (is (= vtt/gm-role (::vtt/viewer-role room)))
        (is (= 1 (count (::vtt/scenes room))))
        (is (= (:db/id scene) (::vtt/active-scene-id room)))
        (is (= "Scene 1" (::vtt/name scene)))
        (is (= 1 (get-in room [::vtt/combat-state ::vtt/round])))
        (is (= "gm-user" (::vtt/username membership)))
        (is (= vtt/gm-role (::vtt/role membership)))))))

(deftest create-room-returns-structured-error-when-creation-fails
  (let [response (with-redefs [d/transact (fn [& _]
                                            (throw (ex-info "boom" {:error :transact-failed})))]
                   (vtt-routes/create-room {:conn ::fake-conn
                                            :identity {:user "gm-user"}
                                            :transit-params {::vtt/name "Arena"}}))]
    (is (= 500 (:status response)))
    (is (= :vtt-room-creation-failed (get-in response [:body :error])))
    (is (= "Unable to create the VTT room. Please try again or contact support."
           (get-in response [:body :message])))))

(deftest room-owner-can-delete-room-but-other-members-cannot
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (seed-users! mocked-conn ["gm-user" "player-one"])
      (let [room (create-room! mocked-conn "gm-user" "Arena")
            room-id (:db/id room)]
        (command! mocked-conn
                  "gm-user"
                  room-id
                  {::vtt/command-type :add-member
                   ::vtt/username "player-one"})
        (let [player-delete (vtt-routes/delete-room {:db (d/db mocked-conn)
                                                     :conn mocked-conn
                                                     :identity {:user "player-one"}
                                                     :path-params {:id room-id}})
              owner-delete (vtt-routes/delete-room {:db (d/db mocked-conn)
                                                    :conn mocked-conn
                                                    :identity {:user "gm-user"}
                                                    :path-params {:id room-id}})
              rooms-after-delete (:body (vtt-routes/list-rooms {:db (d/db mocked-conn)
                                                                :identity {:user "gm-user"}}))]
          (is (= 403 (:status player-delete)))
          (is (= 200 (:status owner-delete)))
          (is (empty? rooms-after-delete)))))))

(deftest gm-can-add-members-but-players-cannot
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (seed-users! mocked-conn ["gm-user" "player-one" "player-two"])
      (let [room (create-room! mocked-conn "gm-user" "Arena")
            room-id (:db/id room)
            add-player (command! mocked-conn
                                 "gm-user"
                                 room-id
                                 {::vtt/command-type :add-member
                                  ::vtt/username "player-one"})
            player-add-attempt (command! mocked-conn
                                         "player-one"
                                         room-id
                                         {::vtt/command-type :add-member
                                          ::vtt/username "player-two"})
            snapshot (snapshot-for mocked-conn "gm-user" room-id)]
        (is (= 200 (:status add-player)))
        (is (= 403 (:status player-add-attempt)))
        (is (= #{"gm-user" "player-one"}
               (set (map ::vtt/username (::vtt/memberships snapshot)))))))))

(deftest token-control-is-enforced-per-player
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (seed-users! mocked-conn ["gm-user" "player-one"])
      (let [room (create-room! mocked-conn "gm-user" "Arena")
            room-id (:db/id room)
            scene-id (active-scene-id room)]
        (command! mocked-conn
                  "gm-user"
                  room-id
                  {::vtt/command-type :add-member
                   ::vtt/username "player-one"})
        (command! mocked-conn
                  "gm-user"
                  room-id
                  {::vtt/command-type :spawn-token
                   ::vtt/scene-id scene-id
                   ::vtt/token {::vtt/name "Hero"
                                ::vtt/token-kind :pc
                                ::vtt/controllers ["player-one"]
                                ::vtt/x 24
                                ::vtt/y 24}})
        (command! mocked-conn
                  "gm-user"
                  room-id
                  {::vtt/command-type :spawn-token
                   ::vtt/scene-id scene-id
                   ::vtt/token {::vtt/name "Ogre"
                                ::vtt/token-kind :monster
                                ::vtt/x 96
                                ::vtt/y 96}})
        (let [snapshot (snapshot-for mocked-conn "gm-user" room-id)
              hero-id (:db/id (token-by-name snapshot "Hero"))
              ogre-id (:db/id (token-by-name snapshot "Ogre"))
              move-hero (command! mocked-conn
                                  "player-one"
                                  room-id
                                  {::vtt/command-type :move-token
                                   ::vtt/token-id hero-id
                                   ::vtt/x 180
                                   ::vtt/y 210})
              move-ogre (command! mocked-conn
                                  "player-one"
                                  room-id
                                  {::vtt/command-type :move-token
                                   ::vtt/token-id ogre-id
                                   ::vtt/x 200
                                   ::vtt/y 240})
              updated-snapshot (snapshot-for mocked-conn "player-one" room-id)
              moved-hero (token-by-name updated-snapshot "Hero")]
          (is (= 200 (:status move-hero)))
          (is (= 403 (:status move-ogre)))
          (is (= 180 (::vtt/x moved-hero)))
          (is (= 210 (::vtt/y moved-hero))))))))

(deftest advance-turn-follows-initiative-order-and-wraps-rounds
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (seed-users! mocked-conn ["gm-user"])
      (let [room (create-room! mocked-conn "gm-user" "Arena")
            room-id (:db/id room)
            scene-id (active-scene-id room)]
        (doseq [[token-name initiative] [["Slow" 5] ["Fast" 15]]]
          (command! mocked-conn
                    "gm-user"
                    room-id
                    {::vtt/command-type :spawn-token
                     ::vtt/scene-id scene-id
                     ::vtt/token {::vtt/name token-name
                                  ::vtt/initiative initiative}}))
        (command! mocked-conn "gm-user" room-id {::vtt/command-type :advance-turn})
        (let [first-snapshot (snapshot-for mocked-conn "gm-user" room-id)
              fast-id (:db/id (token-by-name first-snapshot "Fast"))
              slow-id (:db/id (token-by-name first-snapshot "Slow"))]
          (is (= fast-id (get-in first-snapshot [::vtt/combat-state ::vtt/current-turn-token :db/id])))
          (is (= 1 (get-in first-snapshot [::vtt/combat-state ::vtt/round])))
          (command! mocked-conn "gm-user" room-id {::vtt/command-type :advance-turn})
          (let [second-snapshot (snapshot-for mocked-conn "gm-user" room-id)]
            (is (= slow-id (get-in second-snapshot [::vtt/combat-state ::vtt/current-turn-token :db/id])))
            (is (= 1 (get-in second-snapshot [::vtt/combat-state ::vtt/round]))))
          (command! mocked-conn "gm-user" room-id {::vtt/command-type :advance-turn})
          (let [third-snapshot (snapshot-for mocked-conn "gm-user" room-id)]
            (is (= fast-id (get-in third-snapshot [::vtt/combat-state ::vtt/current-turn-token :db/id])))
            (is (= 2 (get-in third-snapshot [::vtt/combat-state ::vtt/round])))))))))
