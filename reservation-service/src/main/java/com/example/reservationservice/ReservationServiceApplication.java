package com.example.reservationservice;

import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.connectionfactory.R2dbcTransactionManager;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@EnableTransactionManagement
@SpringBootApplication
public class ReservationServiceApplication {

	@Bean
	TransactionalOperator transactionalOperator(ReactiveTransactionManager txm) {
		return TransactionalOperator.create(txm);
	}

	@Bean
	ReactiveTransactionManager transactionManager(ConnectionFactory cf) {
		return new R2dbcTransactionManager(cf);
	}

	@Bean
	RouterFunction<ServerResponse> routes(ReservationRepository rr) {
		return route()
			.GET("/reservations", request -> ok().body(rr.findAll(), Reservation.class))
			.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(ReservationServiceApplication.class, args);
	}
}


@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingRequest {
	private String name;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
	private String message;
}

@Configuration
class GreetingWebsocketConfiguration {

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}

	@Bean
	WebSocketHandler webSocketHandler(GreetingsService gs) {
		return session -> {

			var receive = session
				.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.map(GreetingRequest::new)
				.flatMap(gs::greet)
				.map(GreetingResponse::getMessage)
				.map(session::textMessage);

			return session.send(receive);
		};
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler wsh) {
		return new SimpleUrlHandlerMapping(Map.of("/ws/greetings", wsh), 10);
	}
}

@Service
@RequiredArgsConstructor
class ReservationService {

	private final TransactionalOperator transactionalOperator;
	private final ReservationRepository reservationRepository;

	Flux<Reservation> saveAll(String... names) {
		var stream = Flux
			.fromArray(names)
			.map(name -> new Reservation(null, name))
			.flatMap(this.reservationRepository::save)
			.doOnNext(r -> Assert.isTrue(isValid(r), "name must start with a capital letter!"));

		return this.transactionalOperator.transactional(stream);
	}

	private boolean isValid(Reservation r) {
		var name = r.getName();
		if (name == null) return false;
		var fc = name.charAt(0);
		return Character.isUpperCase(fc);
	}
}

@Controller
class GreetingsService {

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(GreetingRequest request) {
		return Flux
			.fromStream(Stream.generate(() -> new GreetingResponse("Hello, " + request.getName() + " @ " + Instant.now())))
			.delayElements(Duration.ofSeconds(1));
	}
}

@Component
@Log4j2
@RequiredArgsConstructor
class Initializer {

	private final ReservationRepository reservationRepository;
	private final ReservationService reservationService;
	private final TransactionalOperator transactionalOperator;

	@EventListener(ApplicationReadyEvent.class)
	public void ready() {

		var names = this.reservationService.saveAll("Josh", "Zen", "Fish", "Levi", "Blair", "Samuel", "Matt", "Sarah");

		transactionalOperator
			.transactional(
				this.reservationRepository
					.deleteAll()
					.thenMany(names)
					.thenMany(this.reservationRepository.findAll())
			)
			.subscribe(log::info);
	}
}

interface ReservationRepository extends ReactiveCrudRepository<Reservation, Integer> {
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {

	@Id
	private Integer id;
	private String name;
}