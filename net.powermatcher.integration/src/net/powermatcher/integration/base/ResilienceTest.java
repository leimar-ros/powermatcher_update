package net.powermatcher.integration.base;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.zip.DataFormatException;

import net.powermatcher.api.MatcherRole;
import net.powermatcher.api.Session;
import net.powermatcher.api.data.Bid;
import net.powermatcher.api.data.MarketBasis;
import net.powermatcher.core.auctioneer.Auctioneer;
import net.powermatcher.core.sessions.SessionManager;
import net.powermatcher.core.time.SystemTimeService;
import net.powermatcher.integration.util.AuctioneerWrapper;
import net.powermatcher.integration.util.CsvBidReader;
import net.powermatcher.integration.util.CsvExpectedResultsReader;
import net.powermatcher.mock.MockAgent;

import org.junit.After;
import org.junit.Before;

public class ResilienceTest {

    private final static String MATCHERNAME = "auctioneer";

    // Reader for the bid info input file
    protected CsvBidReader bidReader;

    // Reader for the expected results
    protected CsvExpectedResultsReader resultsReader;

    // The market basis
    protected MarketBasis marketBasis;

    // The direct upstream matcher for the agents
    protected AuctioneerWrapper matcherAgent;

    // List of matcher agents (for setting market basis)
    protected List<MatcherRole> matchers;

    // List of agents sending bids from
    protected List<MockAgent> agentList;

    // SessionManager to handle the connections between matcher and agents
    protected SessionManager sessionManager;

    // List of active connections
    protected List<String> activeConnections;

    @After
    public void tearDown() throws IOException {
        if (this.bidReader != null) {
            this.bidReader.closeFile();
        }
        removeAgents(agentList, this.matcherAgent);
    }

    protected void addAgent(MockAgent agent) {
        sessionManager.addAgentRole(agent, agent.getAgentProperties());
    }

    protected void removeAgents(List<MockAgent> agents, MatcherRole matcher) {
        for (MockAgent agent : agents) {
            sessionManager.removeAgentRole(agent, agent.getAgentProperties());
        }
    }

    protected void setMarketBasis(MarketBasis marketBasis) {
        this.marketBasis = marketBasis;
    }

    protected void prepareTest(String testID, String suffix) throws IOException, DataFormatException {
        // Create agent list
        this.agentList = new ArrayList<MockAgent>();

        // Create matcher list
        this.matchers = new ArrayList<MatcherRole>();

        // Active connections
        this.activeConnections = new ArrayList<>();
        // Get the expected results
        this.resultsReader = new CsvExpectedResultsReader(getExpectedResultsFile(testID, suffix));

        this.marketBasis = resultsReader.getMarketBasis();
        this.matcherAgent = new AuctioneerWrapper();
        Map<String, Object> auctioneerProperties = new HashMap<>();
        auctioneerProperties.put("id", "auctioneer");
        auctioneerProperties.put("matcherId", "auctioneer");
        auctioneerProperties.put("agentId", "auctioneer");
        auctioneerProperties.put("commodity", "electricity");
        auctioneerProperties.put("currency", "EUR");
        auctioneerProperties.put("priceSteps", marketBasis.getPriceSteps());
        auctioneerProperties.put("minimumPrice", marketBasis.getMinimumPrice());
        auctioneerProperties.put("maximumPrice", marketBasis.getMaximumPrice());
        auctioneerProperties.put("bidTimeout", "600");
        auctioneerProperties.put("priceUpdateRate", "1");
        auctioneerProperties.put("clusterId", "testCluster");

        this.matchers.add(this.matcherAgent);

        matcherAgent.setExecutorService(new ScheduledThreadPoolExecutor(10));
        matcherAgent.setTimeService(new SystemTimeService());
        matcherAgent.activate(auctioneerProperties);

        Map<String, Object> sessionProperties = new HashMap<>();
        sessionProperties.put("activeConnections", activeConnections);

        // Session
        this.sessionManager = new SessionManager();
        sessionManager.addMatcherRole(matcherAgent, auctioneerProperties);
        sessionManager.activate(sessionProperties);

        // Create the bid reader
        this.bidReader = new CsvBidReader(getBidInputFile(testID, suffix), this.marketBasis);
    }

    protected Bid nextBidToMatcher(int id) throws IOException, DataFormatException {
        MockAgent newAgent;
        Bid bid = this.bidReader.nextBid();

        if (bid != null) {
            newAgent = createAgent(id);
            newAgent.sendBid(bid);
        }

        return bid;
    }

    protected void sendBidsToMatcher(MatcherRole matcher) throws IOException, DataFormatException {
        Bid bid = null;
        MockAgent newAgent;

        double[] aggregatedDemand = new double[this.marketBasis.getPriceSteps()];

        boolean stop = false;
        int i = 0;
        do {
            try {
                bid = this.bidReader.nextBid();

                if (bid != null) {
                    // Aggregated demand calculation
                    double demand[] = bid.getDemand();
                    for (int j = 0; j < demand.length; j++) {
                        aggregatedDemand[j] = aggregatedDemand[j] + demand[j];
                    }

                    if (agentList.size() > i) {
                        newAgent = this.agentList.get(i);
                    } else {
                        newAgent = createAgent(i);
                    }
                    newAgent.sendBid(bid);
                    i++;
                } else {
                    stop = true;
                }
            } catch (InvalidParameterException e) {
                System.err.println("Incorrect bid specification found: " + e.getMessage());
                bid = null;
            }
            matcherAgent.publishNewPrice();
        } while (!stop);

        // Write aggregated demand array
        System.out.print("Aggregated demand: ");
        for (int j = 0; j < aggregatedDemand.length; j++) {
            if (j == (aggregatedDemand.length - 1)) {
                System.out.println(aggregatedDemand[j]);
            } else {
                System.out.print(aggregatedDemand[j] + ",");
            }
        }

    }

    private MockAgent createAgent(int i) {
        String agentId = "agent" + (i + 1);
        MockAgent newAgent = new MockAgent(agentId);
        this.agentList.add(i, newAgent);

        activeConnections.add(agentId + "::" + MATCHERNAME);

        Map<String, Object> sessionProperties = new HashMap<>();
        sessionProperties.put("activeConnections", activeConnections);
        sessionManager.modified(sessionProperties);

        addAgent(newAgent);
        return newAgent;
    }

    public String getExpectedResultsFile(String testID, String suffix) {
        String csvSuffix = null;
        if (suffix == null)
            csvSuffix = ".csv";
        else
            csvSuffix = suffix + ".csv";

        return "input/" + testID + "/AggBidPrice" + csvSuffix;
    }

    public String getBidInputFile(String testID, String suffix) {
        String csvSuffix = null;
        if (suffix == null)
            csvSuffix = ".csv";
        else
            csvSuffix = suffix + ".csv";

        return "input/" + testID + "/Bids" + csvSuffix;
    }

    protected void checkEquilibriumPrice() {
        double expPrice = this.resultsReader.getEquilibriumPrice();

        // TODO this is direct call for the Auctioneer to update its prices
        // because the scheduler doesn't work properly. Remove this once it does
        matcherAgent.publishNewPrice();

        // Verify the price received by the agents
        for (MockAgent agent : agentList) {
            assertEquals(expPrice, agent.getLastPriceUpdate().getCurrentPrice(), 0);
        }
    }

    protected void checkAggregatedBid(Bid aggregatedBid) {
        // Verify the aggregated bid
        assertArrayEquals(this.resultsReader.getAggregatedBid().getDemand(), aggregatedBid.getDemand(), 0);
    }
}
