import java.util.*;


public class WiFiSimulation {
    private List<User> users;
    private Channel channel;
    private AccessPoint accessPoint;
    private double currentTime;
    private double simulationDuration;

    public WiFiSimulation() {
        this.channel = new Channel();
        this.accessPoint = new AccessPoint();
        this.currentTime = 0;
        this.simulationDuration = 1_000_000; // 1 second in microseconds
    }

    public void runSimulation(int numUsers, int packetsPerUser) {
        // Initialize users and packets
        users = new ArrayList<>();
        for (int i = 0; i < numUsers; i++) {
            User user = new User(i);
            for (int j = 0; j < packetsPerUser; j++) {
                user.addPacket(new Packet(1024, 0)); // 1KB packets
            }
            users.add(user);
        }

        // Main simulation loop
        while (currentTime < simulationDuration) {
            // Check if channel becomes free
            if (!channel.isChannelFree(currentTime)) {
                currentTime = channel.getBusyUntilTime();
                continue;
            }

            // Let each user attempt transmission
            for (User user : users) {
                user.attemptTransmission(channel, accessPoint, currentTime);
            }

            // Small time increment to prevent infinite loop
            currentTime += 1;
        }

        // Calculate and display results
        displayResults(numUsers);
    }

    private void displayResults(int numUsers) {
        List<Packet> receivedPackets = accessPoint.getReceivedPackets();
        double totalDataBits = receivedPackets.size() * 1024 * 8;
        double throughputMbps = (totalDataBits / (currentTime / 1_000_000)) / 1_000_000;
        
        double totalLatency = 0;
        double maxLatency = 0;
        for (Packet p : receivedPackets) {
            double latency = p.getLatency();
            totalLatency += latency;
            if (latency > maxLatency) maxLatency = latency;
        }
        double avgLatency = receivedPackets.isEmpty() ? 0 : totalLatency / receivedPackets.size();

        System.out.println("\nResults for " + numUsers + " users:");
        System.out.printf("Throughput: %.2f Mbps\n", throughputMbps);
        System.out.printf("Average Latency: %.2f µs\n", avgLatency);
        System.out.printf("Maximum Latency: %.2f µs\n", maxLatency);
        System.out.println("Total packets delivered: " + receivedPackets.size());
    }

    public static void main(String[] args) {
        WiFiSimulation simulation = new WiFiSimulation();
        
        // Case 1: 1 user
        simulation.runSimulation(1, 1000);
        
        // Case 2: 10 users
        simulation.runSimulation(10, 100);
        
        // Case 3: 100 users
        simulation.runSimulation(100, 10);
    }
}

class Channel {
    private boolean isBusy;
    private double busyUntilTime;
    private int currUserId;

    public Channel() {
        this.isBusy = false;
        this.busyUntilTime = 0;
    }

    public void occupyChannel(double currentTime, double transmissionDuration, int userId) {
        this.isBusy = true;
        this.busyUntilTime = currentTime + transmissionDuration;
        this.currUserId = userId;
    }

    public boolean isChannelFree(double currentTime) {
        return currentTime >= busyUntilTime;
    }

    public double getBusyUntilTime() {
        return busyUntilTime;
    }

    public double getDataRate() {
        double bandwidth = 20_000_000; // 20 MHz in Hz
        int bitsPerSymbol = 8;       // 256-QAM = log2(256) = 8
        double codingRate = 5.0 / 6.0;
        return bandwidth * bitsPerSymbol * codingRate; // in bits/second
    }
}

class BackoffCalculator {
    private static final int CW_MIN = 15;
    private static final int CW_MAX = 1023;
    private static final int SLOT_TIME_MICROSECONDS = 9;
    private Random random;

    public BackoffCalculator() {
        this.random = new Random();
    }

    public int getBackoffTime(int retryCount) {
        int cw = Math.min(CW_MIN * (1 << retryCount), CW_MAX);
        return random.nextInt(cw + 1) * SLOT_TIME_MICROSECONDS;
    }
}

class Packet {
    private int sizeBytes;
    private double creationTime;
    private double receiveTime;

    public Packet(int sizeBytes, double creationTime) {
        this.sizeBytes = sizeBytes;
        this.creationTime = creationTime;
    }

    public int getSizeInBits() {
        return sizeBytes * 8;
    }

    public double getLatency() {
        return receiveTime - creationTime;
    }

    public void setReceiveTime(double time) {
        this.receiveTime = time;
    }
}

class User {
    private int userId;
    private Queue<Packet> packetsToSend;
    private int retryCount;
    private double nextAttemptTime;
    private BackoffCalculator backoffCalc;

    public User(int userId) {
        this.userId = userId;
        this.packetsToSend = new LinkedList<>();
        this.retryCount = 0;
        this.nextAttemptTime = 0;
        this.backoffCalc = new BackoffCalculator();
    }

    public void addPacket(Packet packet) {
        packetsToSend.add(packet);
    }

    public boolean hasPacketsToSend() {
        return !packetsToSend.isEmpty();
    }

    public void attemptTransmission(Channel channel, AccessPoint ap, double currentTime) {
        if (packetsToSend.isEmpty() || currentTime < nextAttemptTime) {
            return;
        }

        Packet packet = packetsToSend.peek();
        if (channel.isChannelFree(currentTime)) {
            // Calculate transmission time in microseconds
            double transmissionTime = (packet.getSizeInBits() / channel.getDataRate()) * 1_000_000;
            channel.occupyChannel(currentTime, transmissionTime, userId);
            packet.setReceiveTime(currentTime + transmissionTime);
            ap.receivePacket(packet);
            packetsToSend.remove();
            retryCount = 0;
        } else {
            int backoffTime = backoffCalc.getBackoffTime(retryCount);
            retryCount++;
            nextAttemptTime = currentTime + backoffTime;
        }
    }
}

class AccessPoint {
    private List<Packet> receivedPackets;
    public AccessPoint() {
        this.receivedPackets = new ArrayList<>();
    }

    public void receivePacket(Packet packet) {
        receivedPackets.add(packet);
    }

    public List<Packet> getReceivedPackets() {
        return receivedPackets;
    }
}