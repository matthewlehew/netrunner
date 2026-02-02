(ns web.game-api
  (:require [cheshire.core :as json]
            [monger.collection :as mc]
            [game.main :as main]
            [jinteki.utils :refer [side-from-str]]
            [web.agent :as agent]
            [web.app-state :as app-state]
            [web.decks :as decks]
            [web.mongodb :refer [->object-id]]
            [web.utils :refer [response]]))

(defn- make-link [host path] (str host path))

(defn- make-card-details [host card]
  (-> card
      (dissoc :_id :images :quantity :format :rotated :normalizedtitle :previous-versions)
      (assoc :image (make-link host (get-in card [:images :en :default :stock])))))

(defn- make-card-info [host card]
  {:qty (:qty card)
   :title (:title (:card card))
   :details (make-card-details host (:card card))})

(defn- get-deck-id [username game]
  (if (:started game)
    (let [state @(:state game)
          side (if (= username (get-in state [:runner :user :username])) :runner :corp)]
      (get-in state [side :deck-id]))
    (:_id (:deck (first (filter #(= username (get-in % [:user :username])) (:players game)))))))

(defn- get-deck [db username game]
  (if-let [deck-id (get-deck-id username game)]
    (decks/update-deck (mc/find-one-as-map db "decks" {:_id (->object-id deck-id) :username username}))
    nil))

(defn- api-handler [{db :system/db
                     scheme :scheme
                     headers :headers
                     :as ctx}
                    action]
  (if-let [api-key (get headers "x-jnet-api")]
    (let [api-uuid (try
                     (java.util.UUID/fromString api-key)
                     (catch Exception e nil))
          api-record (mc/find-one-as-map db "api-keys" {:api-key api-uuid} ["username"])
          username (:username api-record)]
      (if username
        (let [game (app-state/uid-player->lobby username)
              in-game-options (when (:state game) (:options @(:state game)))
              allow-access (or (:api-access game) (:api-access in-game-options))]
          (if (and game allow-access)
            (action username game ctx)
            (response 403 {:message "No game for key or API Access not enabled"})))
        (response 404 {:message "Unknown X-JNet-API key"})))
    (response 400 {:message "No X-JNet-API header specified"})))

(defn decklist-handler [ctx]
  (api-handler ctx
               (fn [username game
                    {db :system/db
                     scheme :scheme
                     headers :headers}]
                 (if-let [deck (get-deck db username game)]
                   (let [host (str (name scheme) "://" (get headers "host"))]
                     (response 200 {:name (:name deck)
                                    :identity {:title (get-in deck [:identity :title])
                                               :details (make-card-details host (:identity deck))}
                                    :cards (map #(make-card-info host %) (:cards deck))}))
                   (response 204 {:message "No deck selected"})))))

(defn- get-side [username state]
  (cond
    (= username (get-in @state [:corp :user :username])) :corp
    (= username (get-in @state [:runner :user :username])) :runner
    :else nil))

(defn- area-handler [ctx area]
  (api-handler ctx
               (fn [username game ctx]
                 (if-let [side (get-side username (:state game))]
                   (let [stack (sort (map :code (get-in @(:state game) [side area])))]
                     (response 200 {:cards stack}))
                   (response 204 {:message "No deck selected"})))))

(defn deck-handler [ctx]
  (area-handler ctx :deck))

(defn hand-handler [ctx]
  (area-handler ctx :hand))

(defn discard-handler [ctx]
  (area-handler ctx :discard))

(defn log-handler [ctx]
  (api-handler ctx
               (fn [username game ctx]
                 (response 200 {:messages (:log @(:state game))}))))

;; ============================================
;; Agent API Endpoints
;; ============================================

(defn- agent-api-handler
  "API handler specifically for agent games. Similar to api-handler but
   verifies the request is from the registered agent for this game."
  [{db :system/db
    scheme :scheme
    headers :headers
    :as ctx}
   action]
  (if-let [api-key (get headers "x-jnet-api")]
    (let [api-uuid (try
                     (java.util.UUID/fromString api-key)
                     (catch Exception e nil))
          api-record (mc/find-one-as-map db "api-keys" {:api-key api-uuid} ["username"])
          username (:username api-record)]
      (if username
        (let [game (app-state/uid-player->lobby username)
              in-game-options (when (:state game) (:options @(:state game)))
              allow-access (or (:api-access game) (:api-access in-game-options))]
          (if (and game allow-access)
            (action username game ctx)
            (response 403 {:message "No game for key or API Access not enabled"})))
        (response 404 {:message "Unknown X-JNet-API key"})))
    (response 400 {:message "No X-JNet-API header specified"})))

(defn- get-player-side
  "Gets the side (keyword) for the given username in the game state"
  [username state]
  (cond
    (= username (get-in @state [:corp :user :username])) :corp
    (= username (get-in @state [:runner :user :username])) :runner
    ;; Check if this is an agent game and the API caller is the agent
    (= "Agent" (get-in @state [:corp :user :username])) :corp
    (= "Agent" (get-in @state [:runner :user :username])) :runner
    :else nil))

(defn state-handler
  "Returns the full game state from the perspective of the requesting player/agent"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)]
          (if side
            (response 200 (agent/get-game-state-for-agent state side))
            (response 403 {:message "Not a player in this game"})))
        (response 404 {:message "Game not started"})))))

(defn prompt-handler
  "Returns the current prompt/choices for the requesting player/agent"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)
              prompt (get-in @state [side :prompt-state])]
          (response 200 {:prompt (agent/format-prompt-for-api prompt)
                         :side (name side)}))
        (response 404 {:message "Game not started"})))))

(defn available-actions-handler
  "Returns the currently available actions for the requesting player/agent"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)]
          (if side
            (response 200 {:actions (agent/compute-available-actions state side)
                           :side (name side)})
            (response 403 {:message "Not a player in this game"})))
        (response 404 {:message "Game not started"})))))

(defn board-handler
  "Returns the current board state (servers, rigs, installed cards)"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)
              servers (agent/format-servers-for-api (get-in @state [:corp :servers]))
              rig (get-in @state [:runner :rig])]
          (response 200 {:servers servers
                         :rig {:hardware (agent/format-cards-for-api (:hardware rig))
                               :program (agent/format-cards-for-api (:program rig))
                               :resource (agent/format-cards-for-api (:resource rig))
                               :facedown (agent/format-cards-for-api (:facedown rig))}
                         :side (name side)}))
        (response 404 {:message "Game not started"})))))

(defn scored-handler
  "Returns scored agendas for both sides"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (response 200 {:corp {:scored (agent/format-cards-for-api (get-in @state [:corp :scored]))
                              :agenda-points (get-in @state [:corp :agenda-point])}
                       :runner {:scored (agent/format-cards-for-api (get-in @state [:runner :scored]))
                                :agenda-points (get-in @state [:runner :agenda-point])}})
        (response 404 {:message "Game not started"})))))

(defn run-handler
  "Returns information about the current run if any"
  [ctx]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [run (:run @state)]
          (response 200 {:run (when run
                                {:server (:server run)
                                 :position (:position run)
                                 :phase (:phase run)
                                 :no-action (:no-action run)
                                 :next-phase (:next-phase run)})
                         :encounters (:encounters @state)}))
        (response 404 {:message "Game not started"})))))

(defn action-handler
  "Executes a game action on behalf of the requesting player/agent.
   Expects JSON body with {:command \"command-name\" :args {...}}"
  [{:keys [body] :as ctx}]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)
              {:keys [command args]} body]
          (if (and side command)
            (try
              (let [lobby game]
                ;; Use the same action handling as websocket commands
                (swap! app-state/app-state
                       update :lobbies
                       (fn [lobbies]
                         (if-let [l (get lobbies (:gameid lobby))]
                           (assoc-in lobbies [(:gameid lobby) :last-update] (java.time.Instant/now))
                           lobbies)))
                ;; Execute the action
                (main/handle-action state side command args)
                (response 200 {:success true
                               :command command
                               :side (name side)}))
              (catch Exception e
                (response 500 {:message (str "Action failed: " (.getMessage e))
                               :command command})))
            (response 400 {:message "Missing command or not a player in this game"})))
        (response 404 {:message "Game not started"})))))

(defn choice-handler
  "Responds to a prompt with a choice. 
   Expects JSON body with {:choice \"choice-value\" or :choice {:uuid \"uuid\"} for card choices}"
  [{:keys [body] :as ctx}]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)
              {:keys [choice]} body]
          (if (and side choice)
            (try
              (main/handle-action state side "choice" {:choice choice})
              (response 200 {:success true
                             :side (name side)})
              (catch Exception e
                (response 500 {:message (str "Choice failed: " (.getMessage e))})))
            (response 400 {:message "Missing choice or not a player in this game"})))
        (response 404 {:message "Game not started"})))))

(defn select-handler
  "Selects a card for targeting prompts.
   Expects JSON body with {:card {:cid \"card-cid\"}}"
  [{:keys [body] :as ctx}]
  (agent-api-handler ctx
    (fn [username game ctx]
      (if-let [state (:state game)]
        (let [side (get-player-side username state)
              {:keys [card]} body]
          (if (and side card)
            (try
              (main/handle-action state side "select" {:card card})
              (response 200 {:success true
                             :side (name side)})
              (catch Exception e
                (response 500 {:message (str "Select failed: " (.getMessage e))})))
            (response 400 {:message "Missing card or not a player in this game"})))
        (response 404 {:message "Game not started"})))))
