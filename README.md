# Quiz Battle

A real-time multiplayer quiz **game** for 2 - 4 players over a network, written in plain Java (Swing + sockets).
EC5205 Operating Systems & Network Programming - Network Programming project.

Everybody sits around a round stadium. A question slides in at the top and every player secretly picks an answer.
Each answer lies **face down** on the table - everybody can see THAT you answered, but not WHAT. When the time is up
**all cards turn over one after another**: right answers ripple green, wrong ones flash red, the points rise and the
scores count up. The questions are about **this very course**
(TCP, UDP, sockets, threads), so a game doubles as exam revision.

## How to run (Windows)

1. Everybody double-clicks **`start_client.bat`** (it compiles first, so there is no separate compile step).
2. **Host:** on the welcome page choose *Host a game*, pick the number of players (2 - 4) and questions,
   type your name, pick your colour and press **CREATE ARENA**.
3. **Friends** (same Wi-Fi / LAN): choose *Join a game*. The game is found automatically - type your name,
   pick a colour that is still free and press **JOIN ARENA**.
   (If nothing is found, type the host's IP address. It is shown at the top of the host's lobby.)
4. The game starts by itself when all seats are taken. The host can also press **START NOW** earlier (minimum 2 players).

You need a JDK (Java 8 or newer). By hand: `javac -d out *.java`, then `java -cp out QuizClient`.
You can test on ONE computer: start the game several times (each window is one player) - the first one hosts,
the others join.

## How to play

| Action | What happens |
|---|---|
| Click an answer card, or press `A` `B` `C` `D` (or `1` `2` `3` `4`) | You lock in your answer. Your first pick counts. Nobody sees WHAT you picked - only that you picked. |
| Click **50 / 50** | Two wrong answers are crossed out (once per game) |
| Time is up (or everybody answered) | **Reveal!** Every answer card turns over, points rise, scores count up, a "Did you know?" fact is shown |
| After the last question | Podium with a spotlight on the winner and drifting gold dust. **BACK TO MENU** to play again |

**Colours:** every player picks one of 6 colours. When somebody takes a colour it is crossed out for everybody else,
live - two players can never have the same colour.

**Scoring:** 100 points for a correct answer + up to 50 speed bonus + 10 per correct answer in a row (streak, max +50).
Wrong or missing answers score 0.

## Add your own questions

Open `questions.txt` - one question per line, no Java needed:

    question | option A | option B | option C | option D | correct letter | fun fact

## How it uses the lecture topics

| Lecture topic | Where in the code |
|---|---|
| **TCP sockets**: `ServerSocket`, `accept()`, `Socket`, `PrintWriter`, `BufferedReader` | `QuizServer` (accept thread), `PlayerHandler`, `GameConnection` - all game traffic |
| **UDP**: `DatagramSocket`, `DatagramPacket` | `Discovery` - the joining player broadcasts "QUIZ_DISCOVER", the host replies, so nobody types an IP |
| **Threads**: `Runnable`, `start()`, `run()` | one `PlayerHandler` thread per player; accept thread; game thread; UDP thread; the client's listener thread (`GameConnection`) |
| **Concurrency / shared data** | `synchronized (QuizServer.lock)` protects the player list, colours, answers and scores |
| **Client-server architecture** | the host's program contains the server; it holds the game state, every client only draws what it is told and sends answers |

Why TCP for the game but UDP for discovery? The game needs every message to arrive, in order (TCP).
Discovery is one tiny "is anybody there?" message where speed matters and a lost packet does not (UDP).

One more rule worth knowing: only the Swing thread may touch the window. The client's listener thread therefore never
changes the screen itself - it hands each message over with `SwingUtilities.invokeLater(...)`.

## The protocol (one line of text per message, parts separated by `|`)

```
Client -> Server
  JOIN|name|colour             take a seat (colour = 0..5)
  START                        start now (only the first player who joined = the host)
  ANSWER|number|letter         answer question <number> with A, B, C or D
  LIFELINE|number              use the 50/50 lifeline

Server -> Client
  ROOM|max|started|name:colour:score:connected,...   who sits where (sent on every change)
  JOINED|name|colour           you have a seat (your final, unique name)
  JOINFAIL|reason              colour taken, room full, game already started ...
  STARTING|3                   countdown 3-2-1-GO
  QUESTION|n|total|seconds|text|A|B|C|D              a new question
  LOCKED|name                  this player answered (the letter stays secret!)
  HINT|x|y                     the two wrong letters removed by 50/50
  REVEAL|correct|fun fact|name:letter:points:streak,...   everybody's answer + points
  INFO|text                    a notice
  GAMEOVER|name1,name2,...     final ranking, best first
```

The answer carries the question number, so a late answer to an old question can never count for the next one.
Time is measured on the server's clock, so nobody can fake a fast answer.

## Files

| File | Job |
|---|---|
| `QuizClient.java` | the game window; connects to the server; can start a server inside itself (hosting) |
| `WelcomeScreen.java` | welcome page: host / join, number of players, name, colour picker |
| `ArenaScreen.java` | the stadium: players, question, answer cards, reveal animations, podium |
| `Ui.java`, `Screen.java` | drawing helpers (colours, glass panels, spaced text, buttons, easing) and the base class of a page |
| `GameConnection.java` | the client's TCP link and its listener thread |
| `QuizServer.java` | the server: lobby, accept thread, game loop, scoring, colour rules |
| `PlayerHandler.java` | one thread per player: reads that player's messages, keeps their score |
| `Discovery.java` | UDP server discovery (both sides) |
| `QuestionBank.java`, `questions.txt` | reads and holds the questions |
| `start_client.bat` | double-click to play (host or join) |
| `start_server.bat` | optional: a server without a window for playing; normally not needed |

## Troubleshooting

- **"Java JDK not found"** - install a JDK (e.g. Eclipse Temurin 21 from adoptium.net) and tick *Add to PATH* in the installer.
  The launcher already searches Android Studio's built-in JDK and the usual install folders by itself.
- **Windows Firewall asks about Java** - click *Allow access* (private networks). The host needs this so friends can connect.
- **"No game found"** - some networks (campus Wi-Fi, VPN on) block broadcasts. Type the host's IP address
  (shown in the host's lobby), or use a phone hotspot / turn the VPN off.
- **"Port 5000 is busy"** - another game or server is already running on this computer; close it first.
- **The window is too big for a small screen** - it scales to fit; you can also resize it freely.
