package com.ticketfactory.web;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.stats.Stats;
import com.ticketfactory.stats.StatsService;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketService;
import com.ticketfactory.ticket.TicketState;
import com.ticketfactory.ticket.Transition;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class DashboardController {

    static final int PAGE_SIZE = 50;

    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final JobQueue queue;
    private final StatsService stats;
    private final FactoryProperties props;
    private final Clock clock;

    public DashboardController(TicketRepository tickets, TicketService ticketService, JobQueue queue,
                               StatsService stats, FactoryProperties props,
                               Clock clock) {
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.queue = queue;
        this.stats = stats;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/tickets";
    }

    @GetMapping("/tickets")
    public String list(@RequestParam(required = false) TicketState state,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        int current = Math.max(1, Math.min(page, 100_000));
        // One extra row tells whether there is an older page.
        List<Ticket> rows = tickets.findPage(state, (current - 1) * PAGE_SIZE, PAGE_SIZE + 1);
        model.addAttribute("tickets", rows.size() > PAGE_SIZE ? rows.subList(0, PAGE_SIZE) : rows);
        model.addAttribute("page", current);
        model.addAttribute("hasOlder", rows.size() > PAGE_SIZE);
        model.addAttribute("stats", stats.compute());
        model.addAttribute("filter", state);
        model.addAttribute("states", TicketState.values());
        model.addAttribute("now", clock.instant());
        model.addAttribute("props", props);
        return "tickets";
    }

    @GetMapping("/tickets/{id}")
    public String detail(@PathVariable long id, Model model) {
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ticket " + id));
        List<Transition> history = tickets.history(id);
        model.addAttribute("t", ticket);
        model.addAttribute("history", history);
        model.addAttribute("jobs", queue.findByTicket(id));
        model.addAttribute("attempts", tickets.attemptsFor(ticket.repo(), ticket.issueNumber()));
        model.addAttribute("now", clock.instant());
        model.addAttribute("props", props);
        return "ticket";
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        Stats current = stats.compute();
        Map<TicketState, Long> shares = new EnumMap<>(TicketState.class);
        current.byState().forEach((state, n) ->
                shares.put(state, current.total() == 0 ? 0 : Math.round(100.0 * n / current.total())));
        model.addAttribute("stats", current);
        model.addAttribute("shares", shares);
        model.addAttribute("states", TicketState.values());
        model.addAttribute("failures", tickets.findAll(TicketState.FAILED, 10));
        model.addAttribute("props", props);
        return "stats";
    }

    @PostMapping("/tickets/{id}/cancel")
    public String cancel(@PathVariable long id, RedirectAttributes redirect) {
        if (tickets.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Who did it goes into the ticket's history.
        String user = CurrentUserAdvice.name();
        boolean cancelled = ticketService.cancel(id, "Cancelled from the dashboard" + (user == null ? "" : " by " + user));
        redirect.addFlashAttribute("message", cancelled ? "Ticket cancelled." : "Ticket was already finished.");
        return "redirect:/tickets/" + id;
    }
}
