(ns polismath.conv-man
  "This is the namesascpe for the "
  (:require [polismath.math.named-matrix :as nm]
            [polismath.math.conversation :as conv]
            [polismath.math.clusters :as clust]
            [polismath.meta.metrics :as met]
            [polismath.components.env :as env]
            [polismath.components.mongo :as mongo]
            [polismath.utils :as utils]
            [clojure.core.matrix.impl.ndarray]
            [clojure.core.async :as async :refer [go go-loop <! >! <!! >!! alts!! alts! chan dropping-buffer put! take!]]
            [clojure.tools.trace :as tr]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [plumbing.core :as pc]
            [schema.core :as s]
            ))


(defn prep-bidToPid
  "Prep function for passing to db/format-for-mongo given bidToPid data"
  [results]
  {:zid (:zid results)
   :bidToPid (:bid-to-pid results)
   :lastVoteTimestamp (:last-vote-timestamp results)})


(defn prep-main
  "Prep function for passing to db/format-for-mongo and on the main polismath collection."
  [results]
  (-> results
      ; REFORMAT BASE CLUSTERS
      (update-in [:base-clusters] clust/fold-clusters)
      ; Whitelist of keys to be included in sent data; removes intermediates
      (assoc :lastVoteTimestamp (:last-vote-timestamp results))
      (assoc :lastModTimestamp (:last-mod-timestamp results))
      (utils/hash-map-subset #{
        :base-clusters
        :group-clusters
        :in-conv
        :lastVoteTimestamp
        :lastModTimestamp
        :n
        :n-cmts
        :pca
        :repness
        :consensus
        :zid
        :user-vote-counts
        :votes-base
        :group-votes})))


(defn handle-profile-data
  "For now, just log profile data. Eventually want to send to influxDB and graphite."
  [conv-man conv & {:keys [recompute n-votes finish-time] :as extra-data}]
  (if-let [prof-atom (:profile-data conv)]
    (let [prof @prof-atom
          tot (apply + (map second prof))
          prof (assoc prof :total tot)]
      (try
        (-> prof
            (assoc :n-ptps (:n conv))
            (merge (utils/hash-map-subset conv #{:n-cmts :zid :last-vote-timestamp})
                   extra-data)
            (->> (mongo/insert (:mongo conv-man) (mongo/math-collection-name "profile"))))
        (catch Exception e
          (log/warn "Unable to submit profile data for zid:" (:zid conv))
          (.printStackTrace e)))
      (log/debug "Profile data for zid" (:zid conv) ": " prof))))

;; XXX WIP; need to flesh out and think about all the ins and outs
;; Also, should place this in conversation, but for now...
(def Conversation
  "A schema for what valid conversations should look like (WIP)"
  {:zid                 s/Int
   :last-vote-timestamp s/Int
   :group-votes         s/Any
   ;; Note: we let all other key-value pairs pass through
   s/Keyword            s/Any})

(defn update-fn
  "This function is what actually gets sent to the conv-actor. In addition to the conversation and vote batches
  up in the channel, we also take an error-callback. Eventually we'll want to pass opts through here as well."
  [conv-man conv votes error-callback]
  (let [start-time (System/currentTimeMillis)
        mongo (:mongo conv-man)]
    (log/info "Starting conversation update for zid:" (:zid conv))
    (try
      ;; Need to expose opts for conv-update through config... XXX
      (let [updated-conv   (conv/conv-update conv votes)
            zid            (:zid updated-conv)
            finish-time    (System/currentTimeMillis)
            ; If this is a recompute, we'll have either :full or :reboot, ow/ want to send false
            recompute      (if-let [rc (:recompute conv)] rc false)]
        (log/info "Finished computng conv-update for zid" zid "in" (- finish-time start-time) "ms")
        (handle-profile-data conv-man
                             updated-conv
                             :finish-time finish-time
                             :recompute recompute
                             :n-votes (count votes))
        ;; Make sure our data has the right shape
        (when-let [validation-errors (s/check Conversation updated-conv)]
          ;; XXX Should really be using throw+ (slingshot) here and throutout the code base
          ;; Also, should put in code for doing smart collapsing of collections...
          (throw (Exception. (str "Validation error: Conversation Value does not match schema: "
                                  validation-errors))))
        ; Format and upload main results
        (doseq [[col-name prep-fn] [["main" prep-main] ; main math results, for client
                                    ["bidtopid" prep-bidToPid]]] ; bidtopid mapping, for server
          ;; XXX Hmmm... format-for-mongo should be abstracted so that it always get's called, and the prep
          ;; function gets taken care of separately; don't need to conplect these
          (->> updated-conv
               prep-fn
               (mongo/zid-upsert mongo (mongo/collection-name mongo col-name))))
        (log/info "Finished uploading mongo results for zid" zid)
        ; Return the updated conv
        updated-conv)
      ; In case anything happens, run the error-callback handler. Agent error handlers do not work here, since
      ; they don't give us access to the votes.
      (catch Exception e
        (error-callback votes start-time (:opts' conv) e)
        ; Wait a second before returning the origin, unmodified conv, to throttle retries
        ;; XXX This shouldn't be here... ???
        (Thread/sleep 1000)
        conv))))


;; Maybe switch over to using this with arbitrary attrs map with zid and other data? XXX
;(defmacro try-with-error-log
  ;[zid message & body]
  ;`(try
     ;~@body
     ;(catch
       ;(log/error ~message (str "(for zid=" ~zid ")")))))


(defn build-update-error-handler
  "Returns a closure that can be called in case there is an update error. The closure gives access to
  the queue so votes can be requeued"
  [conv-man queue conv]
  (fn [votes start-time opts update-error]
    (let [zid-str (str "zid=" (:zid conv))]
      (log/error "Failed conversation update for" zid-str)
      (.printStackTrace update-error)
      ; Try requeing the votes that failed so that if we get more, they'll get replayed
      ; XXX - this could lead to a recast vote being ignored, so maybe the sort should just always happen in
      ; the conv-actor update?
      (try
        (log/info "Preparing to re-queue votes for failed conversation update for" zid-str)
        ;; XXX Should we check if the queue got close due to error or manager close, and log?
        (async/go (async/>! queue [:votes votes]))
        (catch Exception qe
          (log/error "Unable to re-queue votes after conversation update failed for" zid-str)
          (.printStackTrace qe)))
      ; Try to send some failed conversation time metrics, but don't stress if it fails
      (try
        (let [end (System/currentTimeMillis)
              duration (- end start-time)]
          ;; Update to use MetricSender component XXX
          (met/send-metric (:metrics conv-man) "math.pca.compute.fail" duration))
        (catch Exception e
          (log/error "Unable to send metrics for failed compute for" zid-str)))
      ; Try to save conversation state for debugging purposes
      (try
        (conv/conv-update-dump conv votes opts update-error)
        (catch Exception e
          (log/error "Unable to perform conv-update dump for" zid-str))))))


;; XXX This is really bad, now that I think of it. There should be a data-driven declarative specification of
;; what the shape of a conversation is, what is required, what needs to be modified etc, so everything is all
;; in one place. This problem with teh :lastVoteTimestamp and group-votes etc came up precisely because there
;; wasn't "one place to go" for modifying all of the potential points of interest for these kind of changes.

;; XXX However, we shouldn't even be pushing the results to mongo if we didn't actually update anything

(defn restructure-mongo-conv
  [conv]
  (-> conv
      (utils/hash-map-subset #{:rating-mat :lastVoteTimestamp :zid :pca :in-conv :n :n-cmts :group-clusters :base-clusters :group-votes})
      (assoc :last-vote-timestamp (get conv :lastVoteTimestamp)
             :last-mod-timestamp  (get conv :lastModTimestamp))
      ; Make sure there is an empty named matrix to operate on
      (assoc :rating-mat (nm/named-matrix))
      ; Update the base clusters to be unfolded
      (update-in [:base-clusters] clust/unfold-clusters)
      ; Make sure in-conv is a set
      (update-in [:in-conv] set)))


(defn load-or-init
  "Given a zid, either load a minimal set of information from mongo, or if a new zid, create a new conv"
  [conv-man zid & {:keys [recompute]}]
  (if-let [conv (and (not recompute) (mongo/load-conv (:mongo conv-man) zid))]
    (-> conv
        restructure-mongo-conv
        (assoc :recompute :reboot))
    ; would be nice to have :recompute :initial
    (assoc (conv/new-conv) :zid zid :recompute :full)))


(defmacro take-all! [c]
  "Given a channel, takes all values currently in channel and places in a vector. Must be called
  within a go block."
  `(loop [acc# []]
     (let [[v# ~c] (alts! [~c] :default nil)]
       (if (not= ~c :default)
         (recur (conj acc# v#))
         acc#))))


(defn take-all!! [c]
  "Given a channel, takes all values currently in channel and places in a vector. Must be called
  within a go block."
  (loop [acc []]
    (let [[v c] (alts!! [c] :default nil)]
      (if (not= c :default)
        (recur (conj acc v))
        acc))))


(defn split-batches
  "This function splits message batches as sent to conv actor up by the first item in batch vector (:votes :moderation)
  so messages can get processed properly"
  [messages]
  (->> messages
       (group-by first)
       (pc/map-vals
         (fn [labeled-batches]
           (->> labeled-batches
                (map second)
                (flatten))))))


(defrecord ConversationManager [config mongo metrics conversations]
  component/Lifecycle
  (start [component]
    (let [conversations (atom {})]
      (assoc component :conversations conversations)))
  (stop [component]
    ;; Close all our message channels for good measure
    (doseq [[zid {:keys [message-chan]}] @conversations]
      (async/close! message-chan))
    ;; Not sure, but we might want this for GC
    (reset! conversations nil)
    (assoc component :conversations nil)))


(defn queue-message-batch!
  [{:keys [conversations] :as conv-man} message-type zid message-batch recompute]
  (if-let [{:keys [conv message-chan]} (get @conversations zid)]
    ;; Then we already have a go loop running for this
    (>!! message-chan {:message-type message-type :message-batch message-batch})
    ;; Then we need to initialize the conversation and set up the conversation channel and go routine
    (let [conv (load-or-init conv-man zid :recompute recompute) ;; XXX recompute should be env var?
          ;; XXX Need to set up message chan buffer as a env var
          message-chan (chan 100000)]
      (swap! conversations assoc zid {:conv conv :message-chan message-chan})
      ;; However, we don't use the conversations atom conv state, but keep track of it explicitly in the loop,
      ;; ensuring that we don't have race conditions for conv state. The state kept in the atom is basically
      ;; just a convenience.
      (go-loop [conv conv]
        (let [first-msg (<! message-chan)
              msgs (concat [first-msg] (take-all! message-chan))
              {:keys [votes moderation]} (split-batches msgs)
              error-handler (build-update-error-handler message-chan conv)
              conv (-> conv
                       (pc/?> moderation (conv/mod-update moderation))
                       (pc/?> votes (update-fn votes error-handler)))]
          (swap! conversations assoc-in [zid :conv] conv)
          (recur conv))))))


