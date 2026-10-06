# dingsbums

A very simplified miro-like whiteboard: babashka backend, [hammer](https://github.com/sstoehrm/hammer) frontend.

## Run

    npm install
    bb build          # frontend release build into public/js
    bb server         # http://localhost:8080 (bb server <port> for another)

Development: `bb server` plus `npx shadow-cljs watch app` (hot reload; bb serves public/).

## Test

    bb test           # backend
    npm test          # frontend

Design: `docs/superpowers/specs/2026-10-06-whiteboard-design.md`.
