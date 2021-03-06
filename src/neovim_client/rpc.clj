(ns neovim-client.rpc
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [msgpack.clojure-extensions]
            [msgpack.core :as msgpack]
            [neovim-client.message :refer [id value msg-type method params
                                           ->response-msg]
                                   :as msg])
  (:import (java.io DataInputStream DataOutputStream)))

(defn- method-not-found
  [msg]
  (log/error "method not found for msg " msg)
  (str "method not found - " (method msg)))

(defn- create-input-channel
  "Read messages from the input stream, put them on a channel."
  [input-stream]
  (let [chan (async/chan 1024)]
    (async/thread
      (loop []
        (log/info "stream[m] --- in-chan[ ] --- plugin")
        (when-let [msg (msgpack/unpack input-stream)]
          (log/info "stream[ ] ->m in-chan[ ] --- plugin" msg)
          (async/>!! chan msg)
          (log/info "stream[ ] --- in-chan[m] --- plugin" (id msg))
          (recur))))
    chan))

(defn- create-output-channel
  "Make a channel to read messages from, write to output stream."
  [output-stream]
  (let [chan (async/chan 1024)]
    (async/thread
      (loop []
        (log/info "stream[ ] --- out-chan[m] --- plugin")
        (when-let [msg (async/<!! chan)]
          (log/info "stream[ ] m<- out-chan[ ] --- plugin" (id msg))
          (let [packed (msgpack/pack msg)]
            (.write output-stream packed 0 (count packed)))
          (.flush output-stream)
          (log/info "stream[m] --- out-chan[ ] --- plugin" (id msg))
          (recur))))
    chan))

;; ***** Public *****

(defn send-message-async!
  [{:keys [message-table out-chan]} msg callback-fn]
  (when (= msg/+request+ (msg-type msg))
    (swap! message-table assoc (id msg) {:msg msg :fn callback-fn}))
  (log/info "stream[ ] --- out-chan[ ] m<- plugin" msg)
  (async/>!! out-chan msg)
  (log/info "stream[ ] --- out-chan[m] --- plugin" (id msg)))

(defn send-message!
  [component msg]
  (let [p (promise)]
    (send-message-async! component msg (partial deliver p))
    @p))

(defn register-method!
  [{:keys [method-table]} method f]
  (swap! method-table assoc method f))

(defn stop
  "Stop the connection. Right now, this probably only works for debug, when
  connected to socket. Don't think we should be trying to .close STDIO streams."
  [{:keys [input-stream output-stream out-chan in-chan]}]
  (async/close! out-chan)
  (async/close! in-chan)
  ;; TODO - drain the out-chan before closing the output-stream.
  (log/info "closing output stream")
  (.close output-stream)
  (log/info "closing input stream")
  (.close input-stream)
  (log/info "input and output streams closed"))

(defn new
  [input-stream output-stream]
  (let [in-chan (create-input-channel input-stream)
        input-stream (DataInputStream. input-stream)
        message-table (atom {})
        method-table (atom {})
        component {:input-stream input-stream
                   :output-stream output-stream
                   :out-chan (create-output-channel output-stream) 
                   :in-chan in-chan
                   :message-table message-table
                   :method-table method-table}]

    (future
      (try
        (loop []
          (when-let [msg (async/<!! in-chan)]
            (log/info "stream[ ] --- in-chan[ ] ->m plugin" (id msg))
            (condp = (msg-type msg)

              msg/+response+
              (let [f (:fn (get @message-table (id msg)))]
                (swap! message-table dissoc (id msg))
                ;; Don't block the handler to execute this.
                (async/thread (when f (f (value msg)))))

              msg/+request+
              (let [f (get @method-table (method msg) method-not-found)
                    ;; TODO - add async/thread here, remove from methods.
                    result (f msg)]
                (send-message-async!
                  component (->response-msg (id msg) result) nil))

              msg/+notify+
              (let [f (get @method-table (method msg) method-not-found)
                    ;; TODO - see above.
                    result (f msg)]))

            (recur)))
        (catch Throwable t (log/info
                             "Exception in message handler, aborting!"
                             t))))

    component))
