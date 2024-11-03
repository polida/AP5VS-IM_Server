package utb.fai;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;


public class SocketHandler {
	/** mySocket je socket, o který se bude tento SocketHandler starat */
	Socket mySocket;


	/** client ID je øetìzec ve formátu <IP_adresa>:<port> */
	String clientID, username;

	String group = "public";
	List<String> groups = new ArrayList<>(Arrays.asList("public"));


	/**
	 * activeHandlers je reference na mnoinu vech právì bìících SocketHandlerù.
	 * Potøebujeme si ji udrovat, abychom mohli zprávu od tohoto klienta
	 * poslat vem ostatním!
	 */
	ActiveHandlers activeHandlers;

	/**
	 * messages je fronta pøíchozích zpráv, kterou musí mít kaý klient svoji
	 * vlastní - pokud bude je pøetíená nebo nefunkèní klientova sí,
	 * èekají zprávy na doruèení právì ve frontì messages
	 */
	ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<String>(20);

	/**
	 * startSignal je synchronizaèní závora, která zaøizuje, aby oba tasky
	 * OutputHandler.run() a InputHandler.run() zaèaly ve stejný okamik.
	 */
	CountDownLatch startSignal = new CountDownLatch(2);

	/** outputHandler.run() se bude starat o OutputStream mého socketu */
	OutputHandler outputHandler = new OutputHandler();
	/** inputHandler.run() se bude starat o InputStream mého socketu */
	InputHandler inputHandler = new InputHandler();
	/**
	 * protoe v outputHandleru nedovedu detekovat uzavøení socketu, pomùe mi
	 * inputFinished
	 */
	volatile boolean inputFinished = false;

	public SocketHandler(Socket mySocket, ActiveHandlers activeHandlers) {
		this.mySocket = mySocket;
		clientID = mySocket.getInetAddress().toString() + ":" + mySocket.getPort();
		this.activeHandlers = activeHandlers;
	}

	class OutputHandler implements Runnable {
		public void run() {
			OutputStreamWriter writer;
			try {
				System.err.println("DBG>Output handler starting for " + clientID);
				startSignal.countDown();
				startSignal.await();
				System.err.println("DBG>Output handler running for " + clientID);
				writer = new OutputStreamWriter(mySocket.getOutputStream(), "UTF-8");
				writer.flush();
				while (!inputFinished) {
					String m = messages.take();// blokující ètení - pokud není ve frontì zpráv nic, uspi se!
					writer.write(m + "\r\n"); // pokud nìjaké zprávy od ostatních máme,
					writer.flush(); // poleme je naemu klientovi
					System.err.println("DBG>Message sent to " + clientID + ":" + m + "\n");
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.err.println("DBG>Output handler for " + clientID + " has finished.");

		}
	}

	class InputHandler implements Runnable {
		public void run() {
			try {
				System.err.println("DBG>Input handler starting for " + clientID);
				startSignal.countDown();
				startSignal.await();
				System.err.println("DBG>Input handler running for " + clientID);
				String request = "";
				/**
				 * v okamiku, kdy nás Thread pool spustí, pøidáme se do mnoiny
				 * vech aktivních handlerù, aby chodily zprávy od ostatních i nám
				 */
				activeHandlers.add(SocketHandler.this);
				BufferedReader reader = new BufferedReader(new InputStreamReader(mySocket.getInputStream(), "UTF-8"));
				username = reader.readLine();
				while ((request = reader.readLine()) != null) { // pøila od mého klienta nìjaká zpráva?
					// ano - poli ji vem ostatním klientùm
					if (request.startsWith("#")) {
						if (request.startsWith("#setMyName")) {
							if (activeHandlers.userExists(request.substring(11))) {
								continue;
							}
							username = request.substring(11);
							System.out.println("DBG>Client " + clientID + " has set his name to " + username);
							continue;
						}
						if (request.startsWith("#sendPrivate")) {
							String[] parts = request.split(" ");
							String recipient = parts[1];
							request =  "[" + username + "] >> "+ String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
							activeHandlers.sendPrivateMessage(SocketHandler.this, recipient, request);
							continue;
						}
						if (request.startsWith("#join")) {
							groups.add(request.substring(6));
							continue;
						}
						if (request.startsWith("#leave")) {
							groups.remove(request.substring(7));
							continue;
						}
						if (request.startsWith("#groups")) {
							activeHandlers.sendPrivateMessage(SocketHandler.this,username,String.join(", ", groups));
							continue;
						}
					}
					request = "[" + username + "] >> " + request;
					System.out.println(request);
					activeHandlers.sendGroupMessage(SocketHandler.this, groups, request);
				}
				inputFinished = true;
				messages.offer("OutputHandler, wakeup and die!");
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				// remove yourself from the set of activeHandlers
				synchronized (activeHandlers) {
					activeHandlers.remove(SocketHandler.this);
				}
			}
			System.err.println("DBG>Input handler for " + clientID + " has finished.");
		}

	}
}