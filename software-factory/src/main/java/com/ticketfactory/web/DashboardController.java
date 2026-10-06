package com.ticketfactory.web;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.GitHubClient.PrStatus;
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

    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final JobQueue queue;
    private final StatsService stats;
    private final FakeGitHubClient fakeGitHub;
    private final FactoryProperties props;
    private final Clock clock;

    public DashboardController(TicketRepository tickets, TicketService ticketService, JobQueue queue,
                               StatsService stats, FakeGitHubClient fakeGitHub, FactoryProperties props,
                               Clock clock) {
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.queue = queue;
        this.stats = stats;
        this.fakeGitHub = fakeGitHub;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/tickets";
    }

    @GetMapping("/tickets")
    public String list(@RequestParam(required = false) TicketState state, Model model) {
        model.addAttribute("tickets", tickets.findAll(state, 500));
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
        boolean cancelled = ticketService.cancel(id, "Cancelled from the dashboard");
        redirect.addFlashAttribute("message", cancelled ? "Ticket cancelled." : "Ticket was already finished.");
        return "redirect:/tickets/" + id;
    }

    /** Phase 1 stand-in for a human approving the PR on GitHub. */
    @PostMapping("/tickets/{id}/approve")
    public String approve(@PathVariable long id, RedirectAttributes redirect) {
        Ticket t = tickets.get(id);
        if (t.state() != TicketState.AWAITING_APPROVAL || t.prNumber() == null) {
            redirect.addFlashAttribute("message", "Only tickets awaiting approval can be approved.");
        } else {
            fakeGitHub.setPullRequestStatus(t.prNumber(), PrStatus.APPROVED);
            queue.wakeUp(id);
            redirect.addFlashAttribute("message", "PR #" + t.prNumber() + " approved on the fake GitHub.");
        }
        return "redirect:/tickets/" + id;
    }
}
