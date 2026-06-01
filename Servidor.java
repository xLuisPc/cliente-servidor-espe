/**
 * Servidor.java
 * ─────────────────────────────────────────────────────────────────────────────
 * COMPILAR :  javac *.java
 * EJECUTAR :  java Servidor [nombre]     ← nombre es opcional
 *             java Cliente              ← una terminal por cada cliente
 *
 * Ejemplos:
 *   java Servidor                       → nombre automático (hostname)
 *   java Servidor "Sala de estudio"     → nombre personalizado
 * ─────────────────────────────────────────────────────────────────────────────
 */

import java.io.IOException;
import java.net.*;
import java.util.*;

public class Servidor {

    public static void main(String[] args) {

        // Nombre del servidor: primer argumento o "Servidor@hostname" por defecto.
        String nombre;
        try {
            nombre = (args.length > 0)
                    ? args[0]
                    : "Servidor@" + InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            nombre = "Servidor";
        }

        // Historial compartido entre todos los manejadores.
        // Cuando un cliente se une, recibe los mensajes anteriores desde aquí.
        Historial historial = new Historial();

        // Lista sincronizada de clientes activos para hacer broadcast.
        List<ManejadorCliente> clientes =
                Collections.synchronizedList(new ArrayList<>());

        // ── Hilo TCP principal: acepta conexiones de chat ─────────────────────
        try (ServerSocket serverSocket = new ServerSocket(Protocolo.PUERTO)) {

            System.out.println("══════════════════════════════════════════════");
            System.out.println("  Servidor de chat: " + nombre);
            System.out.println("  Puerto TCP : " + Protocolo.PUERTO);
            System.out.println("══════════════════════════════════════════════");

            while (true) {
                // accept() bloquea hasta que llega una nueva conexión TCP.
                Socket socket = serverSocket.accept();
                System.out.println("[TCP] Nueva conexión desde: "
                        + socket.getInetAddress().getHostAddress());

                // Cada cliente recibe su propio hilo para no bloquear la aceptación.
                ManejadorCliente manejador =
                        new ManejadorCliente(socket, clientes, historial);
                new Thread(manejador).start();
            }

        } catch (IOException e) {
            System.err.println("[ERROR] No se pudo iniciar el servidor: " + e.getMessage());
        }
    }

}
