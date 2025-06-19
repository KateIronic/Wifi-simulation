import java.util.*;

public class Wifi5Simulation {
    public static void main(String[] args) {
        for (int numUsers : new int[]{1, 10, 100}) {
            System.out.println("\nSimulation with " + numUsers + " users:");
            Simulator sim = new Simulator(1000.0, numUsers, 100);
            sim.run();
        }
    }
}

enum Types {
    BROADCAST, CSI, DATA
}

class Packet {
    private Types type;
    private int sizeBytes;
    private double arrivalTime;
    // private double sendStartTime;
    private double sendEndTime;
    // private int sourceId;
    private int destId;

    public Packet(Types type, int sizeBytes, double arrivalTime, int sourceId, int destId) {
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.arrivalTime = arrivalTime;
        this.sourceId = sourceId;
        this.destId = destId;
    }

    public Types getType() { return type; }
    public int getSizeBytes() { return sizeBytes; }
    public double getSizeInBits() { return sizeBytes * 8; }
    public double getArrivalTime() { return arrivalTime; }
    public int getDestId() { return destId; }
    public double getLatency() { return sendEndTime - arrivalTime; }
    public void setSendStartTime(double time) { this.sendStartTime = time; }
    public void setSendEndTime(double time) { this.sendEndTime = time; }
}

abstract class Node {
    protected int nodeId;
    protected Queue<Packet> outgoingQueue;
    protected Queue<Packet> incomingQueue;
    protected double lastActivityTime;

    public Node(int id) {
        this.nodeId = id;
        this.outgoingQueue = new LinkedList<>();
        this.incomingQueue = new LinkedList<>();
        this.lastActivityTime = 0.0;
    }

    public abstract void receivePacket(Packet p, double receiveTime);
    public abstract void sendPacket(Packet p);
    public int getId() { return nodeId; }
}

class Wifi5User extends Node {
    protected double dataRate;

    public Wifi5User(int id, double dataRate) {
        super(id);
        this.dataRate = dataRate;
    }

    public Packet createCSIpacket(int apId, double currentTime) {
        return new Packet(Types.CSI, 200, currentTime, this.nodeId, apId);
    }

    @Override
    public void receivePacket(Packet p, double receiveTime) {
        p.setSendEndTime(receiveTime);
        incomingQueue.add(p);
        lastActivityTime = receiveTime;
    }
    @Override
    public void sendPacket(Packet p) {
        outgoingQueue.add(p);
        for (Node destNode : p.getDestNodes()) { 
            if (destNode != null) {
                destNode.receivePacket(p);
            }
        }
        outgoingQueue.remove(p);
    }
}

class Wifi5AccessPoint extends Node {
    private List<Wifi5User> associatedUsers;
    private double mimoWindow = 15.0; // 15 ms MIMO phase
    private double dataRate;
    private Map<Wifi5User, Queue<Packet>> userOutgoingQueues;
    private Scheduler scheduler;

    public Wifi5AccessPoint(int id, double dataRate, List<Wifi5User> users) {
        super(id);
        this.dataRate = dataRate;
        this.associatedUsers = users;
        this.userOutgoingQueues = new HashMap<>();
        for (Wifi5User user : users) {
            userOutgoingQueues.put(user, new LinkedList<>());
        }
        this.scheduler = new Scheduler(users, this);
    }

    public double getMimoWindow() { return mimoWindow; }
    public double getDataRate() { return dataRate; }
    public List<Wifi5User> getAssociatedUsers() { return associatedUsers; }

    public double broadcast(double currentTime, StatsCollector stats) {
        Packet packet = new Packet(Types.BROADCAST, 200, currentTime, nodeId, -1);
        double txTime = packet.getSizeInBits() / dataRate * 1000;
        packet.setSendStartTime(currentTime);
        packet.setSendEndTime(currentTime + txTime);
        for (Wifi5User user : associatedUsers) {
            user.receivePacket(packet, currentTime + txTime);
        }
        lastActivityTime = currentTime + txTime;
        return currentTime + txTime;
    }

    public double collectCSIs(double currentTime, StatsCollector stats) {
        for (Wifi5User user : associatedUsers) {
            Packet csiPacket = user.createCSIpacket(this.nodeId, currentTime);
            double csiTxTime = csiPacket.getSizeInBits() / user.dataRate * 1000;
            csiPacket.setSendStartTime(currentTime);
            csiPacket.setSendEndTime(currentTime + csiTxTime);
            receivePacket(csiPacket, currentTime + csiTxTime);
            currentTime += csiTxTime;
        }
        return currentTime;
    }

    public double scheduleMimoPhase(double currentTime, StatsCollector stats) {
        double startTime = currentTime;
        while (currentTime - startTime < mimoWindow) {
            List<Wifi5User> selectedUsers = scheduler.getUsersForMimo(4);
            if (selectedUsers.isEmpty()) break;
            double maxTxTime = 0;
            for (Wifi5User user : selectedUsers) {
                Queue<Packet> queue = userOutgoingQueues.get(user);
                if (!queue.isEmpty()) {
                    Packet packet = queue.poll();
                    packet.setSendStartTime(currentTime);
                    double txTime = packet.getSizeInBits() / dataRate * 1000;
                    packet.setSendEndTime(currentTime + txTime);
                    user.receivePacket(packet, currentTime + txTime);
                    if (packet.getType() == Types.DATA) {
                        stats.logPacketSent(packet, currentTime + txTime);
                    }
                    maxTxTime = Math.max(maxTxTime, txTime);
                }
            }
            currentTime += maxTxTime;
            lastActivityTime = currentTime;
        }
        return currentTime;
    }

    @Override
    public void receivePacket(Packet p, double receiveTime) {
        p.setSendEndTime(receiveTime);
        incomingQueue.add(p);
        lastActivityTime = receiveTime;
    }

    public void setUserOutgoingQueue(Wifi5User user, Queue<Packet> queue) {
        userOutgoingQueues.put(user, queue);
    }

    public boolean hasPacketsForUser(Wifi5User user) {
        Queue<Packet> queue = userOutgoingQueues.get(user);
        return queue != null && !queue.isEmpty();
    }

    public Packet dequeuePacketForUser(Wifi5User user) {
        Queue<Packet> queue = userOutgoingQueues.get(user);
        return queue != null ? queue.poll() : null;
    }
}

class Scheduler {
    private List<Wifi5User> users;
    private int nextUserIndex = 0;
    private Wifi5AccessPoint ap;

    public Scheduler(List<Wifi5User> users, Wifi5AccessPoint ap) {
        this.users = users;
        this.ap = ap;
    }

    public List<Wifi5User> getUsersForMimo(int maxUsers) {
        List<Wifi5User> selected = new ArrayList<>();
        int count = 0;
        while (count < users.size() && selected.size() < maxUsers) {
            Wifi5User user = users.get(nextUserIndex);
            if (ap.hasPacketsForUser(user)) {
                selected.add(user);
            }
            nextUserIndex = (nextUserIndex + 1) % users.size();
            count++;
        }
        return selected;
    }
}

class StatsCollector {
    private long totalBytesTransmitted;
    private List<Double> packetLatencies;
    private double startTime;

    public StatsCollector(double startTime) {
        this.totalBytesTransmitted = 0;
        this.packetLatencies = new ArrayList<>();
        this.startTime = startTime;
    }

    public void logPacketSent(Packet p, double sendEndTime) {
        if (p.getType() == Types.DATA) {
            totalBytesTransmitted += p.getSizeBytes();
            packetLatencies.add(sendEndTime - p.getArrivalTime());
        }
    }

    public double getThroughput(double currentTime) {
        double duration = currentTime - startTime;
        return duration > 0 ? (totalBytesTransmitted * 1000.0) / duration : 0; // bytes/s
    }

    public double getAverageLatency() {
        if (packetLatencies.isEmpty()) return 0;
        double sum = 0;
        for (double lat : packetLatencies) sum += lat;
        return sum / packetLatencies.size();
    }

    public double getMaxLatency() {
        return packetLatencies.isEmpty() ? 0 : Collections.max(packetLatencies);
    }
}

class Simulator {
    private double currentTime;
    private double endTime;
    private Wifi5AccessPoint ap;
    private StatsCollector stats;

    public Simulator(double endTime, int numUsers, int packetsPerUser) {
        this.currentTime = 0.0;
        this.endTime = endTime;
        double dataRate = 86.7e6 / 1000; // 86.7 Mbps to bits/ms
        List<Wifi5User> users = new ArrayList<>();
        for (int i = 1; i <= numUsers; i++) {
            users.add(new Wifi5User(i, dataRate));
        }
        this.ap = new Wifi5AccessPoint(0, dataRate, users);
        this.stats = new StatsCollector(0.0);

        // Initialize packets
        for (Wifi5User user : users) {
            Queue<Packet> queue = new LinkedList<>();
            for (int i = 0; i < packetsPerUser; i++) {
                queue.add(new Packet(Types.DATA, 1024, 0.0, ap.getId(), user.getId()));
            }
            ap.setUserOutgoingQueue(user, queue);
        }
    }

    public void run() {
        while (currentTime < endTime) {
            currentTime = ap.broadcast(currentTime, stats);
            currentTime = ap.collectCSIs(currentTime, stats);
            currentTime = ap.scheduleMimoPhase(currentTime, stats);
        }
        System.out.printf("Throughput: %.2f bytes/s%n", stats.getThroughput(currentTime));
        System.out.printf("Average Latency: %.2f ms%n", stats.getAverageLatency());
        System.out.printf("Max Latency: %.2f ms%n", stats.getMaxLatency());
    }
}

