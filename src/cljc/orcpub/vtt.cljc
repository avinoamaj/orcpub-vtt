(ns orcpub.vtt
  (:require #?(:clj [clojure.spec.alpha :as spec]
               :cljs [cljs.spec.alpha :as spec])
            [clojure.string :as s]))

(def gm-role :gm)
(def player-role :player)

(def role-order
  {gm-role 0
   player-role 1})

(def default-scene-width 960)
(def default-scene-height 640)
(def default-grid-size 48)
(def default-token-size 48)

(def room-command-types
  #{:add-member
    :advance-turn
    :apply-damage
    :create-scene
    :move-token
    :send-chat
    :set-active-scene
    :set-scene-background
    :set-token-conditions
    :set-token-initiative
    :spawn-token})

(spec/def ::id nat-int?)
(spec/def ::name (spec/and string? #(not (s/blank? %))))
(spec/def ::username (spec/and string? #(not (s/blank? %))))
(spec/def ::role #{gm-role player-role})
(spec/def ::token-kind #{:pc :npc :monster})
(spec/def ::storage-type #{:upload :url})
(spec/def ::kind #{:map :token :image})
(spec/def ::message-kind #{:text :roll :system})
(spec/def ::command-type room-command-types)
(spec/def ::grid-enabled? boolean?)
(spec/def ::fog-enabled? boolean?)
(spec/def ::hidden? boolean?)
(spec/def ::x int?)
(spec/def ::y int?)
(spec/def ::width nat-int?)
(spec/def ::height nat-int?)
(spec/def ::grid-size nat-int?)
(spec/def ::grid-scale string?)
(spec/def ::initiative int?)
(spec/def ::hit-points int?)
(spec/def ::max-hit-points int?)
(spec/def ::conditions (spec/coll-of keyword? :kind vector?))
(spec/def ::controllers (spec/coll-of ::username :kind vector?))
(spec/def ::command (spec/keys :req [::command-type]))

(defn default-scene
  ([] (default-scene "Scene 1"))
  ([scene-name]
   {::name scene-name
    ::grid-enabled? true
    ::grid-size default-grid-size
    ::grid-scale "5 ft"
    ::width default-scene-width
    ::height default-scene-height
    ::fog-enabled? false
    ::tokens []}))

(defn default-combat-state []
  {::round 1
   ::notes ""})

(defn default-client-state []
  {:room-snapshot nil
   :room-list []
   :stream-status :closed
   :selected-scene-id nil
   :selected-token-ids []
   :pending-command-ids []
   :ui-preferences {:panel-collapse {}
                    :zoom 1
                    :selected-tool :select}
   :asset-url ""
   :chat-draft ""
   :member-username ""
   :create-room-name ""})
