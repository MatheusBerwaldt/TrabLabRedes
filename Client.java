import java.net.*;
import java.io.*;
import java.util.Scanner;
 
// Cliente CLI: protocolo por frames "OP LEN" + payload (UTF-8)

public class Client {
 
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Client <server_ip> <port>");
            return;
        }
 
        String hostname = args[0];
        int port = Integer.parseInt(args[1]);
 
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Cliente conectado a " + hostname + ":" + port + ". Comandos: list | put <path> | msg <texto> | quit");
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                if (line == null) break;
                line = line.trim();
                if (line.equalsIgnoreCase("QUIT") || line.equalsIgnoreCase("EXIT") || line.equalsIgnoreCase("quit")) {
                    doQuit(hostname, port);
                    break;
                }
                if (line.equalsIgnoreCase("LIST") || line.equalsIgnoreCase("list")) {
                    doList(hostname, port);
                } else if (line.toUpperCase().startsWith("PUT ") || line.toUpperCase().startsWith("UPLOAD ") || line.toUpperCase().startsWith("put ")) {
                    String path = line.substring(line.indexOf(' ') + 1).trim();
                    doPut(hostname, port, path);
                } else if (line.toUpperCase().startsWith("MSG ") || line.toUpperCase().startsWith("msg ")) {
                    String text = line.substring(line.indexOf(' ') + 1);
                    doMsg(hostname, port, text);
                } else if (!line.isEmpty()) {
                    System.out.println("Comando inválido.");
                }
            }
        }
    }
 
    private static void doList(String host, int port) {
        long start = System.nanoTime();
        long bytesSent = 0L;
        long bytesRecv = 0L;
        try (Socket socket = new Socket(host, port)) {
            OutputStream rawOut = socket.getOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
            InputStream rawIn = socket.getInputStream();
            bytesSent += writeFrame(out, rawOut, "LIST", new byte[0]);
            Frame resp = readFrame(rawIn);
            bytesRecv += resp.headerBytes + resp.payload.length;
            if (!"OK".equals(resp.op)) {
                System.out.println("Erro LIST: " + new String(resp.payload, "UTF-8"));
                logConnection("LIST", start, System.nanoTime(), bytesSent, bytesRecv);
                return;
            }
            String body = new String(resp.payload, "UTF-8");
            if (body.isEmpty()) {
                System.out.println("(vazio)");
            } else {
                System.out.print(body);
            }
            logConnection("LIST", start, System.nanoTime(), bytesSent, bytesRecv);
        } catch (IOException e) {
            System.out.println("Erro LIST: " + e.getMessage());
            logConnection("LIST", start, System.nanoTime(), bytesSent, bytesRecv);
        }
    }
 
    private static void doPut(String host, int port, String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Arquivo não encontrado: " + filePath);
            return;
        }
        long start = System.nanoTime();
        long bytesSent = 0L;
        long bytesRecv = 0L;
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            OutputStream rawOut = socket.getOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
            long size = file.length();
            byte[] headerPayload = (file.getName() + "\n" + size + "\n").getBytes("UTF-8");
            byte[] payload = new byte[headerPayload.length + (int) size];
            System.arraycopy(headerPayload, 0, payload, 0, headerPayload.length);
            int offset = headerPayload.length;
            byte[] buffer = new byte[8192];
            try (BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file))) {
                int read;
                while ((read = fileIn.read(buffer)) != -1) {
                    System.arraycopy(buffer, 0, payload, offset, read);
                    offset += read;
                }
            }
            bytesSent += writeFrame(out, rawOut, "PUT", payload);

            InputStream rawIn = socket.getInputStream();
            Frame resp = readFrame(rawIn);
            bytesRecv += resp.headerBytes + resp.payload.length;
            if ("OK".equals(resp.op)) {
                System.out.println(new String(resp.payload, "UTF-8"));
            } else {
                System.out.println("Erro PUT: " + new String(resp.payload, "UTF-8"));
            }
            logConnection("PUT", start, System.nanoTime(), bytesSent, bytesRecv);
        } catch (IOException e) {
            System.out.println("Erro PUT: " + e.getMessage());
            logConnection("PUT", start, System.nanoTime(), bytesSent, bytesRecv);
        }
    }

    private static void doMsg(String host, int port, String text) {
        long start = System.nanoTime();
        long bytesSent = 0L;
        long bytesRecv = 0L;
        try (Socket socket = new Socket(host, port)) {
            OutputStream rawOut = socket.getOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
            InputStream rawIn = socket.getInputStream();
            byte[] payload = text.getBytes("UTF-8");
            bytesSent += writeFrame(out, rawOut, "MSG", payload);
            Frame resp = readFrame(rawIn);
            bytesRecv += resp.headerBytes + resp.payload.length;
            System.out.println("Resposta: " + resp.op + (resp.payload.length > 0 ? (" " + new String(resp.payload, "UTF-8")) : ""));
            logConnection("MSG", start, System.nanoTime(), bytesSent, bytesRecv);
        } catch (IOException e) {
            System.out.println("Erro MSG: " + e.getMessage());
            logConnection("MSG", start, System.nanoTime(), bytesSent, bytesRecv);
        }
    }

    // Framing helpers
    private static class Frame { String op; byte[] payload; int headerBytes; }
    private static long writeFrame(PrintWriter headerOut, OutputStream rawOut, String op, byte[] payload) throws IOException {
        String header = op + " " + payload.length + "\n";
        byte[] headerBytes = header.getBytes("US-ASCII");
        rawOut.write(headerBytes);
        if (payload.length > 0) {
            rawOut.write(payload);
        }
        rawOut.flush();
        return (long) headerBytes.length + payload.length;
    }
    private static Frame readFrame(InputStream rawIn) throws IOException {
        String header = readLineAscii(rawIn);
        if (header == null) throw new EOFException("Conexão fechada");
        String[] p = header.split(" ");
        if (p.length < 2) throw new IOException("Header inválido: " + header);
        String op = p[0];
        long len = Long.parseLong(p[1]);
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int r = rawIn.read(buf, off, (int)(len - off));
            if (r == -1) break;
            off += r;
        }
        if (off < len) buf = java.util.Arrays.copyOf(buf, off);
        Frame f = new Frame();
        f.op = op;
        f.payload = buf;
        f.headerBytes = header.getBytes("US-ASCII").length + 1; // +\n
        return f;
    }

    private static String readLineAscii(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (baos.size() == 0 && b == -1) return null;
        return baos.toString("US-ASCII");
    }

    private static void doQuit(String host, int port) {
        long start = System.nanoTime();
        long bytesSent = 0L;
        long bytesRecv = 0L;
        try (Socket socket = new Socket(host, port)) {
            OutputStream rawOut = socket.getOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
            InputStream rawIn = socket.getInputStream();
            bytesSent += writeFrame(out, rawOut, "QUIT", new byte[0]);
            // opcional: ler resposta
            Frame resp = readFrame(rawIn);
            bytesRecv += resp.headerBytes + resp.payload.length;
            logConnection("QUIT", start, System.nanoTime(), bytesSent, bytesRecv);
        } catch (IOException ignored) {}
    }

    private static void logConnection(String op, long startNs, long endNs, long bytesSent, long bytesRecv) {
        try {
            long startMs = startNs / 1_000_000L;
            long endMs = endNs / 1_000_000L;
            double durationSec = (endNs - startNs) / 1_000_000_000.0;
            double sendBps = durationSec > 0 ? (bytesSent / durationSec) : 0.0;
            double recvBps = durationSec > 0 ? (bytesRecv / durationSec) : 0.0;
            File logsDir = new File("logs");
            logsDir.mkdirs();
            String fileName = String.format("%s_%d.log", op, startMs);
            File logFile = new File(logsDir, fileName);
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile), "UTF-8"))) {
                pw.println("op=" + op);
                pw.println("startMs=" + startMs);
                pw.println("endMs=" + endMs);
                pw.println("durationSec=" + String.format(java.util.Locale.US, "%.6f", durationSec));
                pw.println("bytesSent=" + bytesSent);
                pw.println("bytesRecv=" + bytesRecv);
                pw.println("sendBps=" + String.format(java.util.Locale.US, "%.2f", sendBps));
                pw.println("recvBps=" + String.format(java.util.Locale.US, "%.2f", recvBps));
            }
        } catch (Exception ignored) {}
    }
}
