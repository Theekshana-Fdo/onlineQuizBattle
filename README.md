# Quiz Battle

A real-time multiplayer quiz **game** for 2 - 4 players over a network, written in plain Java (Swing + sockets).
EC5205 Operating Systems & Network Programming - Network Programming project.

Everybody sits around a round stadium. A question slides in at the top and every player secretly picks an answer.
Each answer lies **face down** on the table - everybody can see THAT you answered, but not WHAT. When the time is up
**all cards turn over one after another**: right answers ripple green, wrong ones flash red, the points rise and the
scores count up. The questions are about **this very course**
(TCP, UDP, sockets, threads), so a game doubles as exam revision.

## How to run (Windows)

The **server creates the arena**, the **clients only join** it.

1. **Server (one computer):** double-click **`start_server.bat`**. It compiles the game, then asks three questions:
   number of players (2 - 4), number of questions and seconds per question. Press Enter to accept the suggestion.
   The arena is open when the window says "Arena 1 is open". Keep this window open.
2. **Players (every computer, including the server's if that person plays too):** double-click **`start_client.bat`**.
   The game window searches the network and finds the arena by itself (it does not matter whether you start it
   before or after the server). Type your name, pick a colour that is still free and press **JOIN ARENA**.
   (If nothing is found, type the server's IP address - the server window prints it.)
3. The game starts when all seats are taken. The first player who joined can also press **START NOW** earlier (minimum 2 players).
4. After the game, players press **BACK TO MENU**. The server opens a fresh arena after a few seconds,
   so you can play again without restarting anything.

You need a JDK (Java 8 or newer). By hand: `javac -d out *.java`, then `java -cp out QuizServer` and `java -cp out QuizClient`.
You can test on ONE computer: start the server, then start the client two or more times (each window is one player).

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
| **UDP**: `DatagramSocket`, `DatagramPacket` | `Discovery` - the joining player broadcasts "QUIZ_DISCOVER", the server replies, so nobody types an IP |
| **Threads**: `Runnable`, `start()`, `run()` | one `PlayerHandler` thread per player; accept thread; game thread; UDP thread; the client's listener thread (`GameConnection`) |
| **Concurrency / shared data** | `synchronized (QuizServer.lock)` protects the player list, colours, answers and scores |
| **Client-server architecture** | the server holds the game state; every client only draws what it is told and sends answers |

Why TCP for the game but UDP for discovery? The game needs every message to arrive, in order (TCP).
Discovery is one tiny "is anybody there?" message where speed matters and a lost packet does not (UDP).

One more rule worth knowing: only the Swing thread may touch the window. The client's listener thread therefore never
changes the screen itself - it hands each message over with `SwingUtilities.invokeLater(...)`.

## The protocol (one line of text per message, parts separated by `|`)

```
Client -> Server
  JOIN|name|colour             take a seat (colour = 0..5)
  START                        start now (only the first player who joined = the leader)
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
| `QuizClient.java` | the game window; connects to the server and passes its messages to the screens |
| `WelcomeScreen.java` | welcome page: finds the arena, name, colour picker, JOIN ARENA |
| `ArenaScreen.java` | the stadium: players, question, answer cards, reveal animations, podium |
| `Ui.java`, `Screen.java` | drawing helpers (colours, glass panels, spaced text, buttons, easing) and the base class of a page |
| `GameConnection.java` | the client's TCP link and its listener thread |
| `QuizServer.java` | the server: creates the arena, lobby, accept thread, game loop, scoring, colour rules |
| `PlayerHandler.java` | one thread per player: reads that player's messages, keeps their score |
| `Discovery.java` | UDP server discovery (both sides) |
| `QuestionBank.java`, `questions.txt` | reads and holds the questions |
| `start_server.bat` | double-click first: creates the arena (asks players / questions / seconds) |
| `start_client.bat` | double-click to play: joins the arena |

## Troubleshooting

- **"Java JDK not found"** - install a JDK (e.g. Eclipse Temurin 21 from adoptium.net) and tick *Add to PATH* in the installer.
  The launcher already searches Android Studio's built-in JDK and the usual install folders by itself.
- **Windows Firewall asks about Java** - click *Allow access* (private networks). The server computer needs this so the others can connect.
- **The join page keeps saying "Waiting for the server..."** - is `start_server.bat` running and showing "Arena is open"?
  Some networks (campus Wi-Fi, VPN on) block the automatic search: type the server's IP address
  (printed in the server window) or use a phone hotspot / turn the VPN off.
- **"Port 5000 is already in use"** (server window) - another server is already running on this computer; close it first.
- **The window is too big for a small screen** - it scales to fit; you can also resize it freely.
