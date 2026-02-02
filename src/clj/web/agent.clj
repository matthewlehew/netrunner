(ns web.agent
  "Agent opponent support for LLM-based game playing.
   Provides functions for creating virtual agent players and managing agent games."
  (:require
   [clojure.string :as str]
   [game.core.diffs :as diffs]
   [game.core.card :refer [get-card]]
   [game.core.board :refer [all-installed]]
   [game.core.payment :refer [can-pay? ->c]]
   [game.core.installing :refer [corp-can-pay-and-install? runner-can-pay-and-install?]]
   [game.core.play-instants :refer [can-play-instant?]]
   [game.core.board :refer [installable-servers]]
   [jinteki.utils :refer [side-from-str]]
   [medley.core :refer [find-first]]))

;; Agent player marker - used as UID for agent players
(def agent-uid :agent)

(defn create-agent-user
  "Creates a user map representing the LLM agent"
  []
  {:username "Agent"
   :_id "agent"
   :emailhash nil
   :isadmin false
   :special false
   :stats {:games-started 0 :games-completed 0}})

(defn create-agent-player
  "Creates a virtual player representing the LLM agent.
   Options should contain:
   - :agent-side - 'Corp' or 'Runner'
   - :agent-deck - processed deck map
   - :agent-webhook-url - URL for webhook notifications
   - :agent-api-key - API key for authenticating with agent"
  [{:keys [agent-side agent-deck agent-webhook-url agent-api-key]}]
  {:user (create-agent-user)
   :uid agent-uid
   :side agent-side
   :deck agent-deck
   :webhook-url agent-webhook-url
   :api-key agent-api-key
   :is-agent true})

(defn agent-player?
  "Returns true if the player is an LLM agent"
  [player]
  (or (= agent-uid (:uid player))
      (:is-agent player)))

(defn get-agent-player
  "Gets the agent player from a lobby if one exists"
  [lobby]
  (find-first agent-player? (:players lobby)))

(defn get-human-player
  "Gets the human player from a lobby (non-agent)"
  [lobby]
  (find-first #(not (agent-player? %)) (:players lobby)))

(defn agent-game?
  "Returns true if the lobby has an agent opponent"
  [lobby]
  (some? (get-agent-player lobby)))

(defn get-agent-side
  "Returns the side (:corp or :runner) that the agent is playing"
  [lobby]
  (when-let [agent (get-agent-player lobby)]
    (side-from-str (:side agent))))

(defn get-agent-config
  "Gets the agent configuration (webhook URL, API key) from the lobby"
  [lobby]
  (when-let [agent (get-agent-player lobby)]
    {:webhook-url (:webhook-url agent)
     :api-key (:api-key agent)
     :side (get-agent-side lobby)
     :gameid (:gameid lobby)}))

;; Game state helpers for agent API

(defn format-card-for-api
  "Formats a card for API response, including only relevant fields"
  [card]
  (when card
    (select-keys card [:cid :code :title :type :side :zone :rezzed :facedown
                       :installed :hosted :counter :advance-counter
                       :current-strength :subroutines :subtypes])))

(defn format-cards-for-api
  "Formats a collection of cards for API response"
  [cards]
  (mapv format-card-for-api cards))

(defn format-server-for-api
  "Formats a server for API response"
  [server]
  {:content (format-cards-for-api (:content server))
   :ices (format-cards-for-api (:ices server))})

(defn format-servers-for-api
  "Formats all servers for API response"
  [servers]
  (reduce-kv
    (fn [acc k v]
      (assoc acc k (format-server-for-api v)))
    {}
    servers))

(defn format-prompt-choices
  "Formats prompt choices for API response"
  [choices]
  (cond
    (keyword? choices) choices
    (map? choices) choices
    (sequential? choices)
    (mapv (fn [choice]
            (if (map? choice)
              (if (-> choice :value :cid)
                ;; Card choice
                {:uuid (:uuid choice)
                 :idx (:idx choice)
                 :card (format-card-for-api (:value choice))}
                ;; String/other choice
                {:uuid (:uuid choice)
                 :idx (:idx choice)
                 :value (:value choice)})
              choice))
          choices)
    :else choices))

(defn format-prompt-for-api
  "Formats a prompt for API response"
  [prompt]
  (when prompt
    {:message (:msg prompt)
     :prompt-type (:prompt-type prompt)
     :choices (format-prompt-choices (:choices prompt))
     :card (when (:card prompt)
             {:title (get-in prompt [:card :title])
              :cid (get-in prompt [:card :cid])})
     :selectable (:selectable prompt)
     :eid (get-in prompt [:eid :eid])}))

(defn get-basic-action-info
  "Returns information about basic actions available to the side"
  [state side]
  (let [clicks (get-in @state [side :click] 0)
        credits (get-in @state [side :credit] 0)
        has-clicks? (pos? clicks)]
    {:clicks clicks
     :credits credits
     :can-act has-clicks?
     :basic-actions
     (when has-clicks?
       (cond-> []
         true (conj {:action "credit" :label "Gain 1 credit" :cost {:clicks 1}})
         true (conj {:action "draw" :label "Draw 1 card" :cost {:clicks 1}})
         (= side :corp)
         (conj {:action "purge" :label "Purge virus counters" :cost {:clicks 3}})))}))

(defn get-playable-cards
  "Returns cards in hand that can be played"
  [state side]
  (let [hand (get-in @state [side :hand] [])]
    (->> hand
         (filter #(:playable %))
         (format-cards-for-api))))

(defn get-installable-cards
  "Returns cards in hand that can be installed"
  [state side]
  (let [hand (get-in @state [side :hand] [])]
    (->> hand
         (filter (fn [card]
                   (let [card-type (:type card)]
                     (if (= side :corp)
                       (contains? #{"Asset" "Upgrade" "ICE" "Agenda"} card-type)
                       (contains? #{"Hardware" "Program" "Resource"} card-type)))))
         (format-cards-for-api))))

(defn compute-available-actions
  "Returns a structured representation of all currently available actions for the given side"
  [state side]
  (let [prompt-state (get-in @state [side :prompt-state])
        clicks (get-in @state [side :click] 0)
        credits (get-in @state [side :credit] 0)
        is-active? (= side (:active-player @state))
        in-run? (some? (:run @state))
        corp-phase-12? (:corp-phase-12 @state)
        runner-phase-12? (:runner-phase-12 @state)]
    (cond
      ;; If there's an active prompt, that takes priority
      prompt-state
      {:action-type :prompt
       :prompt (format-prompt-for-api prompt-state)
       :can-act true}

      ;; Phase 1.2 - start of turn abilities
      (or (and corp-phase-12? (= side :corp))
          (and runner-phase-12? (= side :runner)))
      {:action-type :phase-12
       :message "Start of turn - resolve abilities or continue"
       :available-commands [{:command "end-phase-12" :label "Continue to turn"}
                            {:command "phase-12-pass-priority" :label "Pass priority"}]
       :can-act true}

      ;; Not our turn
      (not is-active?)
      {:action-type :waiting
       :message "Waiting for opponent"
       :can-act false}

      ;; During a run (Corp)
      (and in-run? (= side :corp))
      {:action-type :run-corp
       :message "Runner is making a run"
       :run-info (select-keys (:run @state) [:server :position :phase])
       :can-act false}

      ;; During a run (Runner)
      (and in-run? (= side :runner))
      {:action-type :run-runner
       :run-info (select-keys (:run @state) [:server :position :phase])
       :available-commands [{:command "continue" :label "Continue run"}
                            {:command "jack-out" :label "Jack out"}]
       :can-act true}

      ;; Normal turn with clicks
      (pos? clicks)
      {:action-type :main
       :clicks clicks
       :credits credits
       :basic-actions (get-basic-action-info state side)
       :playable-cards (get-playable-cards state side)
       :installable-cards (get-installable-cards state side)
       :can-act true}

      ;; No clicks, can end turn
      :else
      {:action-type :end-turn
       :message "No clicks remaining"
       :available-commands [{:command "end-turn" :label "End turn"}]
       :can-act true})))

(defn get-game-state-for-agent
  "Returns the full game state formatted for the agent API.
   Only includes information visible to the specified side."
  [state side]
  (let [corp-state (diffs/corp-summary (:corp @state) state :corp)
        runner-state (diffs/runner-summary (:runner @state) state :runner)
        agent-player-state (if (= side :corp) corp-state runner-state)
        opponent-state (if (= side :corp) runner-state corp-state)]
    {:gameid (:gameid @state)
     :turn (:turn @state)
     :active-player (:active-player @state)
     :phase (cond
              (:corp-phase-12 @state) :corp-phase-12
              (:runner-phase-12 @state) :runner-phase-12
              (:run @state) :run
              :else :main)
     :agent-side side
     :agent-state agent-player-state
     :opponent-state (-> opponent-state
                         ;; Remove private info from opponent
                         (dissoc :hand :deck)
                         (assoc :hand-count (:hand-count opponent-state))
                         (assoc :deck-count (:deck-count opponent-state)))
     :run (when (:run @state)
            (select-keys (:run @state) [:server :position :phase :no-action]))
     :available-actions (compute-available-actions state side)
     :log (take-last 20 (:log @state))}))
