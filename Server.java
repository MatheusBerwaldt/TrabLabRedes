import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
 
// Servidor concorrente: recebe comando UPLOAD <nome> <tamanho> e salva arquivo
 
public class Server {
 
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }
 
        int port = Integer.parseInt(args[0]);
 
        // Garante diretório de uploads
        Path uploadsDir = Paths.get("uploads");
        try {
            Files.createDirectories(uploadsDir);
        } catch (IOException e) {
            System.out.println("Não foi possível criar diretório de uploads: " + e.getMessage());
            return;
        }
 
        try (ServerSocket serverSocket = new ServerSocket(port)) {
 
            System.out.println("Server is listening on port " + port);
 
            while (true) {
                Socket socket = serverSocket.accept();
 
                System.out.println("New client connected: " + socket.getRemoteSocketAddress()); 
 
                Thread handler = new Thread(() -> handleClient(socket, uploadsDir));
                handler.start();
            }
 
        } catch (IOException ex) {
            System.out.println("Server exception: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
 
    private static void handleClient(Socket socket, Path uploadsDir) {
        try (Socket s = socket) {
            InputStream rawIn = s.getInputStream();
            OutputStream rawOut = s.getOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
 
            String header = readLineAscii(rawIn);
            if (header == null) {
                return;
            }
            System.out.println("Header recebido: " + header);
 
            String[] parts = header.split(" ");
            if (parts.length < 2) {
                writeFrame(out, rawOut, "ERROR", "Header inválido".getBytes("UTF-8"));
                return;
            }
 
            String command = parts[0].toUpperCase();
            long payloadLen;
            try {
                payloadLen = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                writeFrame(out, rawOut, "ERROR", "LEN inválido".getBytes("UTF-8"));
                return;
            }
 
            byte[] payload = readFully(rawIn, payloadLen);
 
            switch (command) {
                case "PUT": {
                    int firstNl = indexOfByte(payload, (byte) '\n', 0);
                    int secondNl = firstNl >= 0 ? indexOfByte(payload, (byte) '\n', firstNl + 1) : -1;
                    if (firstNl <= 0 || secondNl <= firstNl + 1) {
                        writeFrame(out, rawOut, "ERROR", "Payload PUT inválido".getBytes("UTF-8"));
                        return;
                    }
                    String fileName = new String(payload, 0, firstNl, "UTF-8");
                    String sizeStr = new String(payload, firstNl + 1, (secondNl - firstNl - 1), "UTF-8");
                    long expectedSize;
                    try {
                        expectedSize = Long.parseLong(sizeStr.trim());
                    } catch (NumberFormatException e) {
                        writeFrame(out, rawOut, "ERROR", "Tamanho inválido".getBytes("UTF-8"));
                        return;
                    }
                    long remainingBytes = payload.length - (secondNl + 1);
                    if (remainingBytes != expectedSize) {
                        writeFrame(out, rawOut, "ERROR", "Bytes do arquivo não conferem".getBytes("UTF-8"));
                        return;
                    }
 
                    Path target = ensureUniqueName(uploadsDir, fileName);
                    long start = System.nanoTime();
                    try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                        fos.write(payload, secondNl + 1, (int) expectedSize);
                        fos.flush();
                    }
                    long end = System.nanoTime();
                    double seconds = (end - start) / 1_000_000_000.0;
                    double mbps = (expectedSize * 8.0) / (seconds * 1_000_000.0);
                    System.out.println("Arquivo salvo: " + target.toAbsolutePath() + " (" + expectedSize + " bytes em " + String.format("%.3f", seconds) + "s, ~" + String.format("%.2f", mbps) + " Mbps)");
 
                    writeFrame(out, rawOut, "OK", ("SAVED " + target.getFileName().toString()).getBytes("UTF-8"));
                    break;
                }
                case "LIST": {
                    File dir = uploadsDir.toFile();
                    File[] files = dir.listFiles();
                    StringBuilder sb = new StringBuilder();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) {
                                sb.append("FILE ").append(f.getName()).append(' ').append(f.length()).append('\n');
                            }
                        }
                    }
                    byte[] resp = sb.toString().getBytes("UTF-8");
                    writeFrame(out, rawOut, "OK", resp);
                    break;
                }
                case "MSG": {
                    String text = new String(payload, "UTF-8");
                    System.out.println("MSG recebida: " + text);
                    writeFrame(out, rawOut, "OK", "RECEIVED".getBytes("UTF-8"));
                    break;
                }
                case "QUIT": {
                    writeFrame(out, rawOut, "OK", new byte[0]);
                    break;
                }
                default: {
                    writeFrame(out, rawOut, "ERROR", "Comando desconhecido".getBytes("UTF-8"));
                }
            }
        } catch (IOException e) {
            System.out.println("Erro ao tratar cliente: " + e.getMessage());
        }
    }

    private static void writeFrame(PrintWriter headerOut, OutputStream rawOut, String op, byte[] payload) throws IOException {
        headerOut.println(op + " " + payload.length);
        headerOut.flush();
        if (payload.length > 0) {
            rawOut.write(payload);
            rawOut.flush();
        }
    }

    private static byte[] readFully(InputStream in, long len) throws IOException {
        byte[] buffer = new byte[(int) len];
        int offset = 0;
        while (offset < len) {
            int read = in.read(buffer, offset, (int) (len - offset));
            if (read == -1) break;
            offset += read;
        }
        if (offset < len) {
            return java.util.Arrays.copyOf(buffer, offset);
        }
        return buffer;
    }

    private static int indexOfByte(byte[] data, byte target, int start) {
        for (int i = start; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }

    private static Path ensureUniqueName(Path dir, String originalName) {
        String base = Paths.get(originalName).getFileName().toString();
        Path candidate = dir.resolve(base);
        if (!Files.exists(candidate)) return candidate;
        int dot = base.lastIndexOf('.');
        String name = (dot > 0) ? base.substring(0, dot) : base;
        String ext = (dot > 0) ? base.substring(dot) : "";
        int counter = 1;
        while (true) {
            String next = name + "(" + counter + ")" + ext;
            candidate = dir.resolve(next);
            if (!Files.exists(candidate)) return candidate;
            counter++;
        }
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
}
