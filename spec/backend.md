# Backend

- Simple babashka server
- Storing a session in a single atom
- Each object is just a element in the session map
- the session exisits for 15 min if no body is connected and gets deleted after the 15 mins.
- there is no way of querying the session ids
- there are no user accounts