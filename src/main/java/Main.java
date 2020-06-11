import org.bouncycastle.operator.OperatorCreationException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Scanner;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;


public class Main {
	private static int SERVER_PORT = 9000;
	private static String MACHINE_NAME = "localhost";
	private static final BlockingDeque<String> sendQueue = new LinkedBlockingDeque<>();
	private static Socket clientSocket = null;
	private static DataInputStream input;
	private static DataOutputStream output;
	private static String ID;
	
	private static X509Certificate caCertificate; //CA cert
	private static X509Certificate clientCertificate; // MY cert
	private static X509Certificate connectedClientCertificate; // THEIR cert
	
	private static PrivateKey clientPrivateKey; //MY private key
	
	private static final Scanner in = new Scanner(System.in);
	
	/**
	 * @param args leave blank to run as server, otherwise provide ip and port to connect directly (1.2.3.4 1234)
	 */
	public static void main(String[] args) throws CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, OperatorCreationException {
		System.out.println("Please enter your username and password, if this is the first run enter a username and password of your choice");
		String username = "alice";
		String password = "123";
		System.out.print("Enter Username:");
		String line = in.nextLine();
		if (line.length() > 0) {
			username = line;
		}
		System.out.print("Enter Password:");
		line = in.nextLine();
		if (line.length() > 0) {
			password = line;
		}
		
		System.out.println("Username = " + username);
		System.out.println("Password = " + password);
		
		System.out.println();
		ensureCertificateExists(username, password);
		
		System.out.println();
		System.out.println("Reading client and CA certificates and client private keys from keystore on disk:");
		KeyStore store = CertificateUtils.loadKeyStoreFromPKCS12(username + ".p12", password);
		Certificate[] certChain = store.getCertificateChain(username);
		clientCertificate = (X509Certificate) certChain[0];
		caCertificate = (X509Certificate) certChain[1];
		clientPrivateKey = (PrivateKey) store.getKey(username, password.toCharArray());
		
		System.out.println("Client Public Key (from certificate):\n" + clientCertificate.getPublicKey());
		System.out.println("\nClient Private Key (from keystore):\n" + clientPrivateKey);
		System.out.println("\nCA Public Key (from certificate):\n" + caCertificate.getPublicKey());


//		Initial connection setup
		if (args.length < 3) {
			if (args.length == 2) {
				MACHINE_NAME = args[0];
				SERVER_PORT = Integer.parseInt(args[1]);
			}
			ID = "ServerClient";
			startServer();
			System.out.println("Connection setup on " + MACHINE_NAME + ":" + SERVER_PORT);
			System.out.println("UserID = " + ID);
		} else {
			ID = "ConnectingClient";
			MACHINE_NAME = args[0];
			SERVER_PORT = Integer.parseInt(args[1]);
			setupConnection();
			System.out.println("Connected to " + MACHINE_NAME + ":" + SERVER_PORT);
		}
		System.out.println("Setting up IO streams");
		setupIOStreams();

//		Perform certificate verification
		System.out.println();
		System.out.println("Performing initial certificate verification:");
		sendCertificate(clientCertificate);
		connectedClientCertificate = receiveCertificate();
		validateCertificate(connectedClientCertificate);
		System.out.println("Response from connected client: " + input.readUTF());
		System.out.println("Certificates validation successful");

//		Create threads to allow free flow of messages in both directions
		System.out.println();
		System.out.println("Creating threads for sending and receiving of messages...");
		createThreads();
		System.out.println("Threads created");
		
		
		String inputLine = in.nextLine();
		
		while (!inputLine.equals("EXIT")) {
			sendQueue.add(inputLine);
			inputLine = in.nextLine();
		}
	}
	
	/**
	 * Checks for a keystore (.p12 file) for the given username, and generates a new client certificate if missing.
	 *
	 * @param username
	 * @param password
	 */
	private static void ensureCertificateExists(String username, String password) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException, OperatorCreationException, IOException {
		System.out.println("Checking for keystore for " + username);
		File f = new File(username + ".p12");
		if (!f.exists()) {
			System.out.println("Keystore not found, generating client certificate for new user");
			GenerateClientCert.generateClientCert(username, password);
			System.out.println("Keystore generated");
		} else {
			System.out.println("Keystore found");
		}
	}
	
	/**
	 * Hash the certificate using (algorithm) and compare with the CA signed certificate hash. Use local CA PubKey Copy
	 *
	 * @param cert received from connected client
	 */
	private static void validateCertificate(X509Certificate cert) {
		System.out.println("Validating certificate received was signed by the trusted Certificate Authority:");
		try {
			cert.verify(caCertificate.getPublicKey());
			System.out.println("Certificate validated successfully");
			output.writeUTF("Certificate Accepted");
		}
		catch (CertificateException | NoSuchAlgorithmException | NoSuchProviderException | SignatureException | IOException e) {
			e.printStackTrace();
		}
		catch (InvalidKeyException e) {
			System.out.println("Invalid Certificate, closing connection.");
			try {
				output.writeUTF("Invalid Certificate");
				System.exit(2);
			}
			catch (IOException ioException) {
				ioException.printStackTrace();
			}
		}
	}
	
	/**
	 * Receive the connected client's certificate over the network.
	 *
	 * @return the connected client's certificate
	 */
	private static X509Certificate receiveCertificate() throws IOException, CertificateException {
		System.out.println("Receiving certificate from connected client");
		
		int numBytes = input.readInt();
		byte[] certAsBytes = new byte[numBytes];
		int bytesRead = input.read(certAsBytes, 0, numBytes);
		System.out.println("Number of bytes read: " + bytesRead);
		X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certAsBytes));
		System.out.println("Received certificate from connected client");
		return cert;
	}
	
	/**
	 * Send clients certificate over the network to the connected client
	 *
	 * @param cert to be sent.
	 */
	private static void sendCertificate(X509Certificate cert) throws CertificateEncodingException, IOException {
		System.out.println("Sending certificate");
		
		byte[] frame = cert.getEncoded();
		output.writeInt(frame.length);
		System.out.println("Length of encoded certificate (in bytes): " + frame.length);
		output.write(frame);
	}
	
	/**
	 * Connects the current user to the server.
	 */
	private static void setupConnection() {
		try {
			clientSocket = new Socket(MACHINE_NAME, SERVER_PORT);
		}
		catch (IOException e) {
			System.err.println("Error creating client socket.");
			e.printStackTrace();
		}
	}
	
	/**
	 * Sets up the data input and output streams over the clientSocket
	 */
	private static void setupIOStreams() {
		try {
			input = new DataInputStream(clientSocket.getInputStream());
			output = new DataOutputStream(clientSocket.getOutputStream());
		}
		catch (IOException e) {
			System.err.println("Error creating data stream connection");
			e.printStackTrace();
		}
		catch (NullPointerException e) {
			System.err.println("Please ensure a client has been started as a server before attempting to connect.");
			e.printStackTrace();
		}
	}
	
	/**
	 * Start the server as listening for a connection and connect to the clientSocket
	 */
	private static void startServer() {
		ServerSocket listenSocket;
		
		try {
			System.out.println("Waiting for client to connect...");
			listenSocket = new ServerSocket(SERVER_PORT);
			clientSocket = listenSocket.accept();

//			ClientConnectThread clientConnectThread = new ClientConnectThread(clientSocket);
//			clientConnectThread.start();
		}
		catch (IOException e) {
			System.err.println("Error whilst opening listening socket/connecting client.");
			e.printStackTrace();
		}
		
	}
	
	/**
	 * Creates two threads, one to deal with messages being sent to the server, by the client,
	 * and one to deal with messages being sent to the client, by the server. Must be called after a successful
	 * login has occurred.
	 */
	private static void createThreads() {
		Thread readMsgThread = new Thread(() -> {
			while (true) {
			
			}
		});
		Thread sendMsgThread = new Thread(() -> {
			while (true) {
			
			}
		});
		readMsgThread.start();
		sendMsgThread.start();
	}
}
