(ns orcpub.vtt.broker
  (:import (java.io IOException PipedInputStream PipedOutputStream)
           (java.nio.charset StandardCharsets)
           (java.util UUID)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defonce listeners* (atom {}))

(defn room-members
  [room-id]
  (->> (get @listeners* room-id {})
       vals
       (map :username)
       set))

(defn subscribe!
  [room-id username]
  (let [client-id (str (UUID/randomUUID))
        queue (LinkedBlockingQueue.)
        entry {:username username
               :queue queue}]
    (swap! listeners* update room-id (fnil assoc {}) client-id entry)
    {:client-id client-id
     :queue queue}))

(defn unsubscribe!
  [room-id client-id]
  (swap! listeners*
         (fn [listeners]
           (let [room-listeners (dissoc (get listeners room-id {}) client-id)]
             (if (seq room-listeners)
               (assoc listeners room-id room-listeners)
               (dissoc listeners room-id))))))

(defn publish!
  [room-id event]
  (doseq [{:keys [queue]} (vals (get @listeners* room-id {}))]
    (.offer ^LinkedBlockingQueue queue event)))

(defn- write-bytes!
  [^PipedOutputStream out s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.flush out))

(defn- sse-frame
  [event-name payload]
  (str "event: " event-name "\n"
       "data: " (pr-str payload) "\n\n"))

(defn stream-response
  [room-id username]
  (let [input (PipedInputStream. 32768)
        output (PipedOutputStream. input)
        {:keys [client-id queue]} (subscribe! room-id username)]
    (publish! room-id {:type :presence-changed
                       :room-id room-id})
    (future
      (try
        (write-bytes! output (sse-frame "room" {:type :connected :room-id room-id}))
        (loop []
          (let [event (.poll ^LinkedBlockingQueue queue 15 TimeUnit/SECONDS)]
            (if event
              (write-bytes! output (sse-frame "room" event))
              (write-bytes! output ": heartbeat\n\n"))
            (recur)))
        (catch IOException _
          nil)
        (catch Throwable _
          nil)
        (finally
          (unsubscribe! room-id client-id)
          (publish! room-id {:type :presence-changed
                             :room-id room-id})
          (try
            (.close output)
            (catch Throwable _
              nil)))))
    {:status 200
     :headers {"Content-Type" "text/event-stream; charset=utf-8"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"
               "X-Accel-Buffering" "no"}
     :body input}))
