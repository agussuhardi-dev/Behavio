package id.behavio.core.domain;

import java.util.UUID;

/**
 * Sebuah "bank/PJP" yang ditiru. Berdiri sendiri: punya port + lifecycle.
 * Domain murni (tanpa anotasi framework).
 */
public class Simulator {

    private final UUID id;
    private String name;
    private int port;
    private SimulatorStatus status;
    private SignatureMode signatureMode;

    public Simulator(UUID id, String name, int port,
                     SimulatorStatus status, SignatureMode signatureMode) {
        this.id = id;
        this.name = name;
        this.port = port;
        this.status = status;
        this.signatureMode = signatureMode;
    }

    public static Simulator create(String name, int port) {
        return new Simulator(UUID.randomUUID(), name, port,
                SimulatorStatus.STOPPED, SignatureMode.SIMULATED);
    }

    public void start() { this.status = SimulatorStatus.RUNNING; }
    public void stop() { this.status = SimulatorStatus.STOPPED; }
    public boolean isRunning() { return status == SimulatorStatus.RUNNING; }

    public UUID id() { return id; }
    public String name() { return name; }
    public void rename(String name) { this.name = name; }
    public int port() { return port; }
    public void setPort(int port) { this.port = port; }
    public SimulatorStatus status() { return status; }
    public SignatureMode signatureMode() { return signatureMode; }
    public void setSignatureMode(SignatureMode mode) { this.signatureMode = mode; }
}
