(ns orcpub.vtt.subs
  (:require [re-frame.core :refer [reg-sub]]
            [orcpub.vtt :as vtt]))

(reg-sub
 ::client-state
 (fn [db _]
   (:vtt db)))

(reg-sub
 ::room-list
 :<- [::client-state]
 (fn [client-state _]
   (:room-list client-state)))

(reg-sub
 ::room-snapshot
 :<- [::client-state]
 (fn [client-state _]
   (:room-snapshot client-state)))

(reg-sub
 ::stream-status
 :<- [::client-state]
 (fn [client-state _]
   (:stream-status client-state)))

(reg-sub
 ::selected-scene-id
 :<- [::client-state]
 (fn [client-state _]
   (:selected-scene-id client-state)))

(reg-sub
 ::selected-token-ids
 :<- [::client-state]
 (fn [client-state _]
   (:selected-token-ids client-state)))

(reg-sub
 ::chat-draft
 :<- [::client-state]
 (fn [client-state _]
   (:chat-draft client-state)))

(reg-sub
 ::asset-url
 :<- [::client-state]
 (fn [client-state _]
   (:asset-url client-state)))

(reg-sub
 ::member-username
 :<- [::client-state]
 (fn [client-state _]
   (:member-username client-state)))

(reg-sub
 ::create-room-name
 :<- [::client-state]
 (fn [client-state _]
   (:create-room-name client-state)))

(reg-sub
 ::viewer-role
 :<- [::room-snapshot]
 (fn [room _]
   (::vtt/viewer-role room)))

(reg-sub
 ::can-gm?
 :<- [::viewer-role]
 (fn [role _]
   (= role vtt/gm-role)))

(reg-sub
 ::scene-map
 :<- [::room-snapshot]
 (fn [room _]
   (into {}
         (map (juxt :db/id identity))
         (::vtt/scenes room))))

(reg-sub
 ::active-scene
 :<- [::room-snapshot]
 :<- [::selected-scene-id]
 (fn [[room selected-scene-id] _]
   (let [scene-id (or selected-scene-id (::vtt/active-scene-id room))]
     (some #(when (= scene-id (:db/id %)) %) (::vtt/scenes room)))))

(reg-sub
 ::tokens
 :<- [::active-scene]
 (fn [scene _]
   (::vtt/tokens scene)))

(reg-sub
 ::selected-token
 :<- [::tokens]
 :<- [::selected-token-ids]
 (fn [[tokens selected-token-ids] _]
   (let [selected-id (first selected-token-ids)]
     (some #(when (= selected-id (:db/id %)) %) tokens))))
