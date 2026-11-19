import java.io.IOException;
import java.net.*;

/**
 * Discovery - lets a client FIND the server automatically using UDP.
 * (Lecture: UDP Socket Programming - DatagramSocket and DatagramPacket)
 *
 * Why UDP here?
 *   A new client does not know the server's IP address yet, so it cannot open a TCP
 *   connection. Instead it shouts ONE small datagram to the whole network (a "broadcast"):
 *          "QUIZ_DISCOVER"     -> "is a quiz server running?"
 *   The server listens on UDP port 5001 and replies with:
 *          "QUIZ_SERVER"       -> "yes, I am here!"
 *   The reply arrives FROM the server's own IP address - exactly what the client needs.
 *
 *   UDP is perfect for this: it is connectionless and fast, and if a datagram is lost
 *   nothing bad happens - the client just asks the player to type the IP by hand.
 */
public class Discovery {

    static final int UDP_PORT = 5001;                 // separate from the game's TCP port (5000)
    static final String REQUEST = "QUIZ_DISCOVER";    // what a searching client sends
    static final String REPLY = "QUIZ_SERVER";        // what the server answers

    // ------------------------------------------------------------------
    // SERVER SIDE: a background thread that answers "is anybody there?"
    // ------------------------------------------------------------------
    // Returns the socket so the server can close it when the game ends (null if the port is busy).
    static DatagramSocket startResponder() {
        final DatagramSocket socket;
        try {
            socket = new DatagramSocket(UDP_PORT);
        } catch (SocketException e) {
            System.out.println("(UDP auto-discovery is not available: " + e.getMessage() + ")");
            return null;
        }

        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[256];
            try {
                while (true) {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);          // waits here until a datagram arrives

                    String text = new String(request.getData(), 0, request.getLength());
                    if (text.equals(REQUEST)) {
                        byte[] answer = REPLY.getBytes();
                        // send the reply straight back to whoever asked
                        socket.send(new DatagramPacket(answer, answer.length,
                                request.getAddress(), request.getPort()));
                    }
                }
            } catch (IOException e) {
                // the socket was closed: the server has stopped
            }
        });
        thread.setDaemon(true);   // do not keep the program alive just for this thread
        thread.start();
        return socket;
    }

    // ------------------------------------------------------------------
    // CLIENT SIDE: ask the network for a server. Returns its IP, or null if nobody answers.
    // ------------------------------------------------------------------
    static String findServer() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);       // allow sending to the whole network
            socket.setSoTimeout(2000);       // wait at most 2 seconds for an answer

            byte[] data = REQUEST.getBytes();
            sendTo(socket, data, "127.0.0.1");         // a server on THIS computer
            sendTo(socket, data, "255.255.255.255");   // a server anywhere on the local network
            sendToEveryNetwork(socket, data);          // ...and once on EACH network card (see below)

            DatagramPacket reply = new DatagramPacket(new byte[256], 256);
            socket.receive(reply);           // throws SocketTimeoutException if nobody answers

            String text = new String(reply.getData(), 0, reply.getLength());
            if (text.equals(REPLY)) {
                return reply.getAddress().getHostAddress();   // the sender's IP = the server's IP
            }
        } catch (IOException e) {
            // timeout or network problem: fall through and report "not found"
        }
        return null;
    }

    /**
     * On Windows the address 255.255.255.255 often leaves through only ONE network card - and that can be the
     * wrong one (a VPN, VirtualBox, a phone-hotspot adapter ...). So we also send the datagram to the
     * broadcast address of EVERY network card (for example 192.168.1.255). One of them is the Wi-Fi of the server.
     */
    private static void sendToEveryNetwork(DatagramSocket socket, byte[] data) {
        try {
            for (NetworkInterface card : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!card.isUp() || card.isLoopback()) continue;            // only cards that are working
                for (InterfaceAddress address : card.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();         // null for IPv6 cards
                    if (broadcast != null) sendTo(socket, data, broadcast.getHostAddress());
                }
            }
        } catch (SocketException e) {
            // cannot list the network cards - the two sends above may still work
        }
    }

    /** Sends one datagram to one address. Problems are ignored (the other address may still work). */
    private static void sendTo(DatagramSocket socket, byte[] data, String address) {
        try {
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(address), UDP_PORT));
        } catch (IOException e) {
            // this address is not reachable right now - ignore
        }
    }
}
