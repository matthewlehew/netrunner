# Agent API Documentation

This document describes the API for integrating an LLM-based agent opponent into Netrunner games on jinteki.net.

## Overview

The agent system allows you to create games where one side (Corp or Runner) is controlled by an external LLM agent rather than a human player. The agent receives webhook notifications when action is required and can query/act via REST API endpoints.

## Game Creation Flow

1. Human creates a game with "Play against AI Agent" option enabled
2. Human configures:
   - Which side the agent plays (Corp/Runner)
   - Which deck the agent uses (from the human's deck collection)
   - Webhook URL for agent notifications
   - API key for authenticating webhook calls
3. Human selects their own deck
4. Human starts the game
5. Game server adds agent player and initializes game state
6. Agent receives `game-started` webhook notification

## Webhook Notifications

The agent receives POST requests to the configured webhook URL with the following format:

```json
{
  "event": "<event-type>",
  "payload": {
    "gameid": "<game-uuid>",
    ...event-specific data...
  },
  "timestamp": 1234567890123
}
```

Headers:
- `Content-Type: application/json`
- `X-Agent-API-Key: <configured-api-key>`
- `X-Event-Type: <event-type>`

### Event Types

#### `game-started`
Sent when the game begins.
```json
{
  "agent-side": "Corp",
  "turn": 0,
  "opponent-identity": "The Catalyst: Convention Breaker"
}
```

#### `turn-started`
Sent when the agent's turn begins.
```json
{
  "turn": 1,
  "clicks": 3,
  "credits": 5,
  "hand-size": 5,
  "available-actions": {...}
}
```

#### `prompt`
Sent when the agent needs to respond to a prompt (e.g., making a choice, selecting a card).
```json
{
  "prompt": {
    "message": "Choose a card to trash",
    "prompt-type": "select",
    "choices": [...],
    "card": {"title": "Hedge Fund", "cid": "card-123"},
    "selectable": ["card-456", "card-789"],
    "eid": 42
  },
  "available-actions": {...}
}
```

#### `checkpoint`
Sent at game checkpoints when the agent may need to act.
```json
{
  "available-actions": {...},
  "turn": 1,
  "active-player": "corp"
}
```

#### `waiting`
Sent when the agent is waiting for the opponent's action.
```json
{
  "message": "Waiting for opponent action"
}
```

#### `opponent-acted`
Sent when the opponent has taken an action.
```json
{
  "available-actions": {...},
  "turn": 1,
  "active-player": "runner"
}
```

#### `run-started`
Sent when a run begins.
```json
{
  "server": "HQ",
  "position": 0,
  "phase": "initiation",
  "is-runner": true,
  "available-actions": {...}
}
```

#### `run-ended`
Sent when a run ends.
```json
{
  "successful": true,
  "available-actions": {...}
}
```

#### `game-ended`
Sent when the game ends.
```json
{
  "winner": "Corp",
  "reason": "Agenda points",
  "agent-won": true,
  "final-scores": {"corp": 7, "runner": 4}
}
```

## REST API Endpoints

All endpoints require the `X-JNet-API` header with a valid API key.

Base URL: `https://jinteki.net`

### Read Endpoints

#### GET `/game/state`
Returns the full game state from the agent's perspective.

Response:
```json
{
  "gameid": "<uuid>",
  "turn": 1,
  "active-player": "corp",
  "phase": "main",
  "agent-side": "corp",
  "agent-state": {...player state...},
  "opponent-state": {...public opponent state...},
  "run": null,
  "available-actions": {...},
  "log": [...]
}
```

#### GET `/game/prompt`
Returns the current prompt if any.

Response:
```json
{
  "prompt": {
    "message": "Choose an option",
    "prompt-type": "choice",
    "choices": [
      {"uuid": "abc-123", "idx": 0, "value": "Yes"},
      {"uuid": "def-456", "idx": 1, "value": "No"}
    ],
    "card": null
  },
  "side": "corp"
}
```

#### GET `/game/actions`
Returns currently available actions.

Response:
```json
{
  "actions": {
    "action-type": "main",
    "clicks": 3,
    "credits": 5,
    "basic-actions": {...},
    "playable-cards": [...],
    "installable-cards": [...],
    "can-act": true
  },
  "side": "corp"
}
```

#### GET `/game/board`
Returns the current board state (servers, rig).

Response:
```json
{
  "servers": {
    "hq": {"content": [...], "ices": [...]},
    "rd": {"content": [...], "ices": [...]},
    "archives": {"content": [...], "ices": [...]},
    "remote1": {"content": [...], "ices": [...]}
  },
  "rig": {
    "hardware": [...],
    "program": [...],
    "resource": [...],
    "facedown": [...]
  },
  "side": "corp"
}
```

#### GET `/game/scored`
Returns scored agendas for both sides.

Response:
```json
{
  "corp": {
    "scored": [...],
    "agenda-points": 4
  },
  "runner": {
    "scored": [...],
    "agenda-points": 2
  }
}
```

#### GET `/game/run`
Returns current run information if any.

Response:
```json
{
  "run": {
    "server": "HQ",
    "position": 1,
    "phase": "approach-ice",
    "no-action": false,
    "next-phase": null
  },
  "encounters": {...}
}
```

#### GET `/game/hand`
Returns cards in agent's hand.

Response:
```json
{
  "cards": ["26048", "30041", "30056", ...]
}
```

#### GET `/game/log`
Returns game log messages.

Response:
```json
{
  "messages": [
    {"user": "__system__", "text": "Corp takes 1 credit."},
    ...
  ]
}
```

### Action Endpoints

#### POST `/game/action`
Execute a game action.

Request:
```json
{
  "command": "credit",
  "args": {}
}
```

Response:
```json
{
  "success": true,
  "command": "credit",
  "side": "corp"
}
```

#### POST `/game/choice`
Respond to a prompt with a choice.

Request (string choice):
```json
{
  "choice": "Yes"
}
```

Request (card choice by UUID):
```json
{
  "choice": {"uuid": "abc-123"}
}
```

Response:
```json
{
  "success": true,
  "side": "corp"
}
```

#### POST `/game/select`
Select a card for targeting prompts.

Request:
```json
{
  "card": {"cid": "card-456"}
}
```

Response:
```json
{
  "success": true,
  "side": "corp"
}
```

## Available Commands

These are the command strings that can be used with `/game/action`:

### Basic Actions
- `"credit"` - Gain 1 credit (1 click)
- `"draw"` - Draw 1 card (1 click)
- `"purge"` - Purge virus counters (3 clicks, Corp only)
- `"remove-tag"` - Remove 1 tag (1 click, 2 credits, Runner only)

### Card Actions
- `"play"` - Play a card from hand
  - Args: `{"card": {"cid": "card-123"}}`
- `"ability"` - Use a card ability
  - Args: `{"card": {"cid": "card-123"}, "ability": 0}`
- `"advance"` - Advance a card (1 click, 1 credit, Corp only)
  - Args: `{"card": {"cid": "card-123"}}`

### Run Actions (Runner)
- `"run"` - Initiate a run
  - Args: `{"server": "HQ"}` (or "R&D", "Archives", "Server 1", etc.)
- `"continue"` - Continue through run phases
- `"jack-out"` - Jack out of a run

### Turn Management
- `"start-turn"` - Start your turn
- `"end-turn"` - End your turn
- `"end-phase-12"` - End start-of-turn phase

### Prompt Responses
- `"choice"` - Respond to a prompt
  - Args: `{"choice": "option-text"}` or `{"choice": {"uuid": "abc-123"}}`
- `"select"` - Select a card for targeting
  - Args: `{"card": {"cid": "card-123"}}`

## Example Agent Flow

1. Receive `game-started` notification
2. If it's agent's turn, receive `turn-started` notification
3. Query `/game/state` to understand the game situation
4. Query `/game/actions` to see available actions
5. Execute action via `/game/action` with appropriate command
6. If prompted, receive `prompt` notification
7. Respond via `/game/choice` or `/game/select`
8. Repeat steps 3-7 until turn ends
9. Execute `/game/action` with `"end-turn"` command
10. Wait for opponent's turn
11. Receive `checkpoint` notifications if action windows occur
12. Repeat until game ends

## Error Handling

All endpoints return appropriate HTTP status codes:
- `200` - Success
- `400` - Bad request (missing parameters)
- `403` - Forbidden (not in game, API access not enabled, or not authorized)
- `404` - Not found (unknown API key, game not started)
- `500` - Server error (action failed)

Error responses include a message:
```json
{
  "message": "Description of the error"
}
```

## MCP Server Integration

For LLM agents using the Model Context Protocol (MCP), implement tools that wrap these endpoints:

```typescript
const agentTools = {
  "get_game_state": {
    description: "Get full game state",
    handler: () => fetch("/game/state", { headers: { "X-JNet-API": apiKey } })
  },
  "get_available_actions": {
    description: "Get currently available actions",
    handler: () => fetch("/game/actions", { headers: { "X-JNet-API": apiKey } })
  },
  "execute_action": {
    description: "Execute a game action",
    parameters: { command: "string", args: "object" },
    handler: (command, args) => fetch("/game/action", {
      method: "POST",
      headers: { "X-JNet-API": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({ command, args })
    })
  },
  "respond_to_prompt": {
    description: "Respond to a prompt",
    parameters: { choice: "string | object" },
    handler: (choice) => fetch("/game/choice", {
      method: "POST",
      headers: { "X-JNet-API": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({ choice })
    })
  }
};
```

## Security Considerations

1. The agent webhook URL should be HTTPS
2. Validate the `X-Agent-API-Key` header on incoming webhooks
3. API keys should be kept secret
4. The agent can only see information visible to its side (no cheating!)
5. Rate limiting may be applied to prevent abuse
