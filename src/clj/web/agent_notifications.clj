(ns web.agent-notifications
  "Webhook notification system for LLM agent opponents.
   Sends notifications to the agent when game events require their attention."
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.core.async :refer [go thread]]
   [taoensso.timbre :as timbre]
   [web.agent :as agent]))

(def ^:private notification-timeout-ms 5000)
(def ^:private max-retries 3)
(def ^:private retry-delay-ms 1000)

(defn- send-webhook!
  "Sends a webhook notification to the agent. Returns true on success."
  [{:keys [webhook-url api-key]} event-type payload]
  (when (and webhook-url api-key)
    (try
      (let [body (json/generate-string
                   {:event event-type
                    :payload payload
                    :timestamp (System/currentTimeMillis)})]
        (http/post webhook-url
                   {:headers {"Content-Type" "application/json"
                              "X-Agent-API-Key" api-key
                              "X-Event-Type" (name event-type)}
                    :body body
                    :socket-timeout notification-timeout-ms
                    :connection-timeout notification-timeout-ms})
        (timbre/debug "Agent notification sent:" event-type)
        true)
      (catch Exception e
        (timbre/warn "Failed to send agent notification:" (.getMessage e))
        false))))

(defn- send-webhook-with-retry!
  "Sends a webhook with retry logic"
  [config event-type payload]
  (thread
    (loop [attempts 0]
      (when (< attempts max-retries)
        (if (send-webhook! config event-type payload)
          :success
          (do
            (Thread/sleep (* retry-delay-ms (inc attempts)))
            (recur (inc attempts))))))))

(defn notify-agent!
  "Main entry point for sending agent notifications.
   Extracts agent config from lobby and sends async notification."
  [lobby event-type payload]
  (when-let [config (agent/get-agent-config lobby)]
    (send-webhook-with-retry! config event-type
                              (assoc payload :gameid (:gameid config)))))

;; Specific notification types

(defn notify-game-started!
  "Notifies the agent that the game has started"
  [lobby state]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (notify-agent! lobby :game-started
                     {:agent-side (name agent-side)
                      :turn (:turn @state)
                      :opponent-identity (get-in @state [(if (= agent-side :corp) :runner :corp) :identity :title])}))))

(defn notify-turn-started!
  "Notifies the agent that their turn has started"
  [lobby state side]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (when (= agent-side side)
        (notify-agent! lobby :turn-started
                       {:turn (:turn @state)
                        :clicks (get-in @state [side :click])
                        :credits (get-in @state [side :credit])
                        :hand-size (count (get-in @state [side :hand]))
                        :available-actions (agent/compute-available-actions state side)})))))

(defn notify-prompt!
  "Notifies the agent that they have a prompt to respond to"
  [lobby state side prompt]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (when (= agent-side side)
        (notify-agent! lobby :prompt
                       {:prompt (agent/format-prompt-for-api prompt)
                        :available-actions (agent/compute-available-actions state side)})))))

(defn notify-waiting-for-opponent!
  "Notifies the agent that they are waiting for the human opponent's action.
   This lets the agent know no action is required from them."
  [lobby state side]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (when (= agent-side side)
        (notify-agent! lobby :waiting
                       {:message "Waiting for opponent action"})))))

(defn notify-opponent-acted!
  "Notifies the agent that the opponent has taken an action.
   Includes updated game state so agent can track game progress."
  [lobby state]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)
          agent-actions (agent/compute-available-actions state agent-side)]
      (notify-agent! lobby :opponent-acted
                     {:available-actions agent-actions
                      :turn (:turn @state)
                      :active-player (:active-player @state)}))))

(defn notify-run-started!
  "Notifies the agent that a run has started"
  [lobby state run]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (notify-agent! lobby :run-started
                     {:server (:server run)
                      :position (:position run)
                      :phase (:phase run)
                      :is-runner (= agent-side :runner)
                      :available-actions (agent/compute-available-actions state agent-side)}))))

(defn notify-run-ended!
  "Notifies the agent that a run has ended"
  [lobby state successful?]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)]
      (notify-agent! lobby :run-ended
                     {:successful successful?
                      :available-actions (agent/compute-available-actions state agent-side)}))))

(defn notify-game-ended!
  "Notifies the agent that the game has ended"
  [lobby state winner reason]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)
          agent-won? (= (name agent-side) winner)]
      (notify-agent! lobby :game-ended
                     {:winner winner
                      :reason reason
                      :agent-won agent-won?
                      :final-scores {:corp (get-in @state [:corp :agenda-point])
                                     :runner (get-in @state [:runner :agenda-point])}}))))

(defn notify-checkpoint!
  "Notifies the agent at a game checkpoint if action may be required.
   Called during the game engine checkpoint to give agent opportunity to respond."
  [lobby state]
  (when (agent/agent-game? lobby)
    (let [agent-side (agent/get-agent-side lobby)
          actions (agent/compute-available-actions state agent-side)]
      ;; Only notify if the agent can actually act
      (when (:can-act actions)
        (notify-agent! lobby :checkpoint
                       {:available-actions actions
                        :turn (:turn @state)
                        :active-player (:active-player @state)})))))
