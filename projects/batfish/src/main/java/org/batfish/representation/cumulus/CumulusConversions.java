package org.batfish.representation.cumulus;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static org.batfish.common.util.CollectionUtil.toImmutableSortedMap;
import static org.batfish.datamodel.Configuration.DEFAULT_VRF_NAME;
import static org.batfish.datamodel.MultipathEquivalentAsPathMatchMode.EXACT_PATH;
import static org.batfish.datamodel.MultipathEquivalentAsPathMatchMode.PATH_LENGTH;
import static org.batfish.datamodel.bgp.VniConfig.importRtPatternForAnyAs;
import static org.batfish.representation.cumulus.BgpProcess.BGP_UNNUMBERED_IP;
import static org.batfish.representation.cumulus.CumulusRoutingProtocol.VI_PROTOCOLS_MAP;
import static org.batfish.representation.cumulus.OspfInterface.DEFAULT_OSPF_DEAD_INTERVAL;
import static org.batfish.representation.cumulus.OspfInterface.DEFAULT_OSPF_HELLO_INTERVAL;
import static org.batfish.representation.cumulus.OspfProcess.DEFAULT_OSPF_PROCESS_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.common.Warnings;
import org.batfish.common.util.CommonUtil;
import org.batfish.datamodel.AsPathAccessList;
import org.batfish.datamodel.AsPathAccessListLine;
import org.batfish.datamodel.BgpActivePeerConfig;
import org.batfish.datamodel.BgpPeerConfig;
import org.batfish.datamodel.BgpUnnumberedPeerConfig;
import org.batfish.datamodel.CommunityList;
import org.batfish.datamodel.CommunityListLine;
import org.batfish.datamodel.ConcreteInterfaceAddress;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.GeneratedRoute;
import org.batfish.datamodel.InterfaceAddress;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.LongSpace;
import org.batfish.datamodel.OriginType;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.PrefixSpace;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.bgp.AddressFamilyCapabilities;
import org.batfish.datamodel.bgp.BgpConfederation;
import org.batfish.datamodel.bgp.EvpnAddressFamily;
import org.batfish.datamodel.bgp.Ipv4UnicastAddressFamily;
import org.batfish.datamodel.bgp.Layer2VniConfig;
import org.batfish.datamodel.bgp.Layer3VniConfig;
import org.batfish.datamodel.bgp.RouteDistinguisher;
import org.batfish.datamodel.bgp.community.ExtendedCommunity;
import org.batfish.datamodel.ospf.OspfArea;
import org.batfish.datamodel.ospf.OspfInterfaceSettings;
import org.batfish.datamodel.routing_policy.Common;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.CallExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.DestinationNetwork;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.ExplicitPrefixSet;
import org.batfish.datamodel.routing_policy.expr.LiteralCommunity;
import org.batfish.datamodel.routing_policy.expr.LiteralOrigin;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.NamedPrefixSet;
import org.batfish.datamodel.routing_policy.expr.Not;
import org.batfish.datamodel.routing_policy.expr.SelfNextHop;
import org.batfish.datamodel.routing_policy.expr.WithEnvironmentExpr;
import org.batfish.datamodel.routing_policy.statement.CallStatement;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.SetNextHop;
import org.batfish.datamodel.routing_policy.statement.SetOrigin;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements;
import org.batfish.datamodel.vxlan.Layer2Vni;

/** Utilities that convert Cumulus-specific representations to vendor-independent model. */
@ParametersAreNonnullByDefault
public final class CumulusConversions {
  private static final int AGGREGATE_ROUTE_ADMIN_COST = 200; // TODO verify this

  private static final Prefix LOOPBACK_PREFIX = Prefix.parse("127.0.0.0/8");

  public static final int DEFAULT_STATIC_ROUTE_ADMINISTRATIVE_DISTANCE = 1;
  public static final int DEFAULT_STATIC_ROUTE_METRIC = 0;
  private static final int MAX_ADMINISTRATIVE_COST = 32767;

  public static final Ip CLAG_LINK_LOCAL_IP = Ip.parse("169.254.40.94");

  @VisibleForTesting
  static GeneratedRoute GENERATED_DEFAULT_ROUTE =
      GeneratedRoute.builder().setNetwork(Prefix.ZERO).setAdmin(MAX_ADMINISTRATIVE_COST).build();

  @VisibleForTesting
  static final Statement REJECT_DEFAULT_ROUTE =
      new If(
          Common.matchDefaultRoute(), ImmutableList.of(Statements.ReturnFalse.toStaticStatement()));
  /**
   * Conversion factor for interface speed units. In the config Mbps are used, VI model expects bps
   */
  public static final double SPEED_CONVERSION_FACTOR = 10e6;

  // Follow the default setting of Cisco.
  // TODO: need to verify this
  public static final double DEFAULT_LOOPBACK_BANDWIDTH = 8e9;

  public static @Nonnull String computeBgpCommonExportPolicyName(String vrfName) {
    return String.format("~BGP_COMMON_EXPORT_POLICY:%s~", vrfName);
  }

  @VisibleForTesting
  public static @Nonnull String computeBgpPeerExportPolicyName(
      String vrfName, String peerInterface) {
    return String.format("~BGP_PEER_EXPORT_POLICY:%s:%s~", vrfName, peerInterface);
  }

  static String computeBgpDefaultRouteExportPolicyName(boolean ipv4, String vrf, String peer) {
    return String.format(
        "~BGP_DEFAULT_ROUTE_PEER_EXPORT_POLICY:IPv%s:%s:%s~", ipv4 ? "4" : "6", vrf, peer);
  }

  public static @Nonnull String computeBgpPeerImportPolicyName(String vrf, String peer) {
    return String.format("~BGP_PEER_IMPORT_POLICY:%s:%s~", vrf, peer);
  }

  public static String computeBgpGenerationPolicyName(boolean ipv4, String vrfName, String prefix) {
    return String.format("~AGGREGATE_ROUTE%s_GEN:%s:%s~", ipv4 ? "" : "6", vrfName, prefix);
  }

  public static String computeMatchSuppressedSummaryOnlyPolicyName(String vrfName) {
    return String.format("~MATCH_SUPPRESSED_SUMMARY_ONLY:%s~", vrfName);
  }

  private static WithEnvironmentExpr bgpRedistributeWithEnvironmentExpr(
      BooleanExpr expr, OriginType originType) {
    WithEnvironmentExpr we = new WithEnvironmentExpr();
    we.setExpr(expr);
    we.setPreStatements(
        ImmutableList.of(Statements.SetWriteIntermediateBgpAttributes.toStaticStatement()));
    we.setPostStatements(
        ImmutableList.of(Statements.UnsetWriteIntermediateBgpAttributes.toStaticStatement()));
    we.setPostTrueStatements(
        ImmutableList.of(
            Statements.SetReadIntermediateBgpAttributes.toStaticStatement(),
            new SetOrigin(new LiteralOrigin(originType, null))));
    return we;
  }

  static BooleanExpr generateExportAggregateConditions(
      Map<Prefix, BgpVrfAddressFamilyAggregateNetworkConfiguration> aggregateNetworks) {
    return new Disjunction(
        aggregateNetworks.entrySet().stream()
            .map(
                entry -> {
                  Prefix prefix = entry.getKey();

                  // Conditions to generate this route
                  List<BooleanExpr> exportAggregateConjuncts = new ArrayList<>();
                  exportAggregateConjuncts.add(
                      new MatchPrefixSet(
                          DestinationNetwork.instance(),
                          new ExplicitPrefixSet(new PrefixSpace(PrefixRange.fromPrefix(prefix)))));
                  exportAggregateConjuncts.add(new MatchProtocol(RoutingProtocol.AGGREGATE));

                  // TODO consider attribute map
                  BooleanExpr weInterior = BooleanExprs.TRUE;
                  exportAggregateConjuncts.add(
                      bgpRedistributeWithEnvironmentExpr(weInterior, OriginType.IGP));

                  // Do export a generated aggregate.
                  return new Conjunction(exportAggregateConjuncts);
                })
            .collect(ImmutableList.toImmutableList()));
  }

  /**
   * Creates generated routes and route generation policies for aggregate routes for the input vrf.
   */
  static void generateGeneratedRoutes(
      Configuration c,
      org.batfish.datamodel.Vrf vrf,
      Map<Prefix, BgpVrfAddressFamilyAggregateNetworkConfiguration> aggregateNetworks) {
    aggregateNetworks.forEach(
        (prefix, agg) -> {
          generateGenerationPolicy(c, vrf.getName(), prefix);

          // TODO generate attribute policy
          GeneratedRoute gr =
              GeneratedRoute.builder()
                  .setNetwork(prefix)
                  .setAdmin(AGGREGATE_ROUTE_ADMIN_COST)
                  .setGenerationPolicy(
                      computeBgpGenerationPolicyName(true, vrf.getName(), prefix.toString()))
                  .setDiscard(true)
                  .build();

          vrf.getGeneratedRoutes().add(gr);
        });
  }

  /**
   * Creates a generation policy for the aggregate network with the given {@link Prefix}. The
   * generation policy matches any route with a destination more specific than {@code prefix}.
   *
   * @param c {@link Configuration} in which to create the generation policy
   * @param vrfName Name of VRF in which the aggregate network exists
   * @param prefix The aggregate network prefix
   */
  static void generateGenerationPolicy(Configuration c, String vrfName, Prefix prefix) {
    RoutingPolicy.builder()
        .setOwner(c)
        .setName(computeBgpGenerationPolicyName(true, vrfName, prefix.toString()))
        .addStatement(
            new If(
                // Match routes with destination networks more specific than prefix.
                new MatchPrefixSet(
                    DestinationNetwork.instance(),
                    new ExplicitPrefixSet(new PrefixSpace(PrefixRange.moreSpecificThan(prefix)))),
                ImmutableList.of(Statements.ReturnTrue.toStaticStatement()),
                ImmutableList.of(Statements.ReturnFalse.toStaticStatement())))
        .build();
  }

  /**
   * Generates and returns a {@link Statement} that suppresses routes that are summarized by the
   * given set of {@link Prefix prefixes} configured as {@code summary-only}.
   *
   * <p>Returns {@code null} if {@code prefixesToSuppress} has no entries.
   *
   * <p>If any Batfish-generated structures are generated, does the bookkeeping in the provided
   * {@link Configuration} to ensure they are available and tracked.
   */
  @Nullable
  static If suppressSummarizedPrefixes(
      Configuration c, String vrfName, Stream<Prefix> summaryOnlyPrefixes) {
    Iterator<Prefix> prefixesToSuppress = summaryOnlyPrefixes.iterator();
    if (!prefixesToSuppress.hasNext()) {
      return null;
    }
    // Create a RouteFilterList that matches any network longer than a prefix marked summary only.
    RouteFilterList matchLonger =
        new RouteFilterList(computeMatchSuppressedSummaryOnlyPolicyName(vrfName));
    prefixesToSuppress.forEachRemaining(
        p ->
            matchLonger.addLine(
                new RouteFilterLine(LineAction.PERMIT, PrefixRange.moreSpecificThan(p))));
    // Bookkeeping: record that we created this RouteFilterList to match longer networks.
    c.getRouteFilterLists().put(matchLonger.getName(), matchLonger);

    return new If(
        "Suppress more specific networks for summary-only aggregate-address networks",
        new MatchPrefixSet(
            DestinationNetwork.instance(), new NamedPrefixSet(matchLonger.getName())),
        ImmutableList.of(Statements.Suppress.toStaticStatement()),
        ImmutableList.of());
  }

  static void convertBgpProcess(Configuration c, CumulusNodeConfiguration vsConfig, Warnings w) {
    BgpProcess bgpProcess = vsConfig.getBgpProcess();
    if (bgpProcess == null) {
      return;
    }
    // First pass: only core processes
    c.getDefaultVrf()
        .setBgpProcess(toBgpProcess(c, vsConfig, DEFAULT_VRF_NAME, bgpProcess.getDefaultVrf()));
    // We make one VI process per VRF because our current datamodel requires it
    bgpProcess
        .getVrfs()
        .forEach(
            (vrfName, bgpVrf) ->
                c.getVrfs().get(vrfName).setBgpProcess(toBgpProcess(c, vsConfig, vrfName, bgpVrf)));

    // Create dud processes for other VRFs that use L3 VNIs, so we can have proper RIBs
    c.getVrfs()
        .forEach(
            (vrfName, vrf) -> {
              Vrf vsVrf = vsConfig.getVrfs().get(vrfName);
              if (vsVrf != null
                  && vsVrf.getVni() != null // has L3 VNI
                  && vrf.getBgpProcess() == null // process does not already exist
                  && c.getDefaultVrf().getBgpProcess() != null) { // there is a default BGP proc
                vrf.setBgpProcess(
                    org.batfish.datamodel.BgpProcess.builder()
                        .setRouterId(c.getDefaultVrf().getBgpProcess().getRouterId())
                        .setAdminCostsToVendorDefaults(ConfigurationFormat.CUMULUS_NCLU)
                        .build());
              }
            });

    /*
     * Second pass: Add neighbors.
     * Requires all VRFs & bgp processes in a VRF to be set in VI so that we can initialize address families
     * that access other VRFs (e.g., EVPN)
     */
    Iterables.concat(ImmutableSet.of(bgpProcess.getDefaultVrf()), bgpProcess.getVrfs().values())
        .forEach(
            bgpVrf -> {
              Long localAs = bgpVrf.getAutonomousSystem();
              org.batfish.datamodel.BgpProcess viBgpProcess =
                  c.getVrfs().get(bgpVrf.getVrfName()).getBgpProcess();
              bgpVrf
                  .getNeighbors()
                  .forEach(
                      (neighborName, neighbor) -> {
                        if (neighbor instanceof BgpInterfaceNeighbor) {
                          BgpInterfaceNeighbor interfaceNeighbor = (BgpInterfaceNeighbor) neighbor;
                          interfaceNeighbor.inheritFrom(bgpVrf.getNeighbors());
                          addInterfaceNeighbor(
                              c, vsConfig, interfaceNeighbor, localAs, bgpVrf, viBgpProcess, w);
                        } else if (neighbor instanceof BgpIpNeighbor) {
                          BgpIpNeighbor ipNeighbor = (BgpIpNeighbor) neighbor;
                          ipNeighbor.inheritFrom(bgpVrf.getNeighbors());
                          addIpv4BgpNeighbor(
                              c, vsConfig, ipNeighbor, localAs, bgpVrf, viBgpProcess, w);
                        } else if (!(neighbor instanceof BgpPeerGroupNeighbor)) {
                          throw new IllegalArgumentException(
                              "Unsupported BGP neighbor type: "
                                  + neighbor.getClass().getSimpleName());
                        }
                      });
            });
  }

  /**
   * Returns {@link org.batfish.datamodel.BgpProcess} for named {@code bgpVrf} if valid, or else
   * {@code null}.
   */
  @Nullable
  static org.batfish.datamodel.BgpProcess toBgpProcess(
      Configuration c, CumulusNodeConfiguration vsConfig, String vrfName, BgpVrf bgpVrf) {
    BgpProcess bgpProcess = vsConfig.getBgpProcess();
    Ip routerId = bgpVrf.getRouterId();
    if (routerId == null) {
      routerId = inferRouterId(vsConfig.getLoopback(), vsConfig.getInterfaces());
    }
    int ebgpAdmin = RoutingProtocol.BGP.getDefaultAdministrativeCost(c.getConfigurationFormat());
    int ibgpAdmin = RoutingProtocol.IBGP.getDefaultAdministrativeCost(c.getConfigurationFormat());
    org.batfish.datamodel.BgpProcess newProc =
        new org.batfish.datamodel.BgpProcess(routerId, ebgpAdmin, ibgpAdmin);
    newProc.setMultipathEquivalentAsPathMatchMode(EXACT_PATH);
    /*
      BGP multipath enabled by default
      https://docs.cumulusnetworks.com/cumulus-linux/Layer-3/Border-Gateway-Protocol-BGP/#maximum-paths
    */
    newProc.setMultipathEbgp(true);
    newProc.setMultipathIbgp(true);
    if (firstNonNull(bgpVrf.getAsPathMultipathRelax(), Boolean.FALSE)) {
      newProc.setMultipathEquivalentAsPathMatchMode(PATH_LENGTH);
    }
    Long confederationId = bgpProcess.getDefaultVrf().getConfederationId();
    Long asn = bgpProcess.getDefaultVrf().getAutonomousSystem();
    if (confederationId != null && asn != null) {
      // TODO: there probably is another way to define confederation members
      newProc.setConfederation(new BgpConfederation(confederationId, ImmutableSet.of(asn)));
    }

    BgpIpv4UnicastAddressFamily ipv4Unicast = bgpVrf.getIpv4Unicast();
    if (ipv4Unicast != null) {
      // Add networks from network statements to new process's origination space
      ipv4Unicast.getNetworks().keySet().forEach(newProc::addToOriginationSpace);

      // Generate aggregate routes
      generateGeneratedRoutes(c, c.getVrfs().get(vrfName), ipv4Unicast.getAggregateNetworks());
    }

    generateBgpCommonExportPolicy(c, vrfName, bgpVrf, vsConfig.getRouteMaps());

    return newProc;
  }

  private static void addInterfaceNeighbor(
      Configuration c,
      CumulusNodeConfiguration vsConfig,
      BgpInterfaceNeighbor neighbor,
      @Nullable Long localAs,
      BgpVrf bgpVrf,
      org.batfish.datamodel.BgpProcess newProc,
      Warnings w) {
    if (neighbor.getRemoteAs() == null && neighbor.getRemoteAsType() == null) {
      w.redFlag("Skipping invalidly configured BGP peer " + neighbor.getName());
      return;
    }
    BgpUnnumberedPeerConfig.Builder peerConfigBuilder =
        BgpUnnumberedPeerConfig.builder()
            .setLocalIp(BGP_UNNUMBERED_IP)
            .setPeerInterface(neighbor.getName());
    generateBgpCommonPeerConfig(c, vsConfig, neighbor, localAs, bgpVrf, newProc, peerConfigBuilder);
  }

  @VisibleForTesting
  static void generateBgpCommonPeerConfig(
      Configuration c,
      CumulusNodeConfiguration vsConfig,
      BgpNeighbor neighbor,
      @Nullable Long localAs,
      BgpVrf bgpVrf,
      org.batfish.datamodel.BgpProcess newProc,
      BgpPeerConfig.Builder<?, ?> peerConfigBuilder) {

    RoutingPolicy exportRoutingPolicy = computeBgpNeighborExportRoutingPolicy(c, neighbor, bgpVrf);
    @Nullable
    RoutingPolicy importRoutingPolicy = computeBgpNeighborImportRoutingPolicy(c, neighbor, bgpVrf);

    peerConfigBuilder
        .setBgpProcess(newProc)
        .setConfederation(bgpVrf.getConfederationId())
        .setDescription(neighbor.getDescription())
        .setGroup(neighbor.getPeerGroup())
        .setLocalAs(localAs)
        .setRemoteAsns(computeRemoteAsns(neighbor, localAs))
        .setEbgpMultihop(neighbor.getEbgpMultihop() != null)
        .setGeneratedRoutes(
            bgpDefaultOriginate(neighbor) ? ImmutableSet.of(GENERATED_DEFAULT_ROUTE) : null)
        // Ipv4 unicast is enabled by default
        .setIpv4UnicastAddressFamily(
            convertIpv4UnicastAddressFamily(
                neighbor.getIpv4UnicastAddressFamily(),
                bgpVrf.getDefaultIpv4Unicast(),
                exportRoutingPolicy,
                importRoutingPolicy))
        .setEvpnAddressFamily(
            toEvpnAddressFamily(
                c, vsConfig, neighbor, localAs, bgpVrf, newProc, exportRoutingPolicy))
        .build();
  }

  @VisibleForTesting
  @Nullable
  static Ipv4UnicastAddressFamily convertIpv4UnicastAddressFamily(
      @Nullable BgpNeighborIpv4UnicastAddressFamily ipv4UnicastAddressFamily,
      boolean defaultIpv4Unicast,
      RoutingPolicy exportRoutingPolicy,
      @Nullable RoutingPolicy importRoutingPolicy) {

    // check if address family should be activated
    boolean explicitActivationSetting =
        ipv4UnicastAddressFamily != null && ipv4UnicastAddressFamily.getActivated() != null;
    if ((explicitActivationSetting && !ipv4UnicastAddressFamily.getActivated())
        || (!explicitActivationSetting && !defaultIpv4Unicast)) {
      return null;
    }

    // According to the docs, the neighbor must have been explicitly activated for
    // route-reflector-client to take effect:
    // https://docs.cumulusnetworks.com/display/DOCS/Border+Gateway+Protocol+-+BGP#BorderGatewayProtocol-BGP-RouteReflectors
    //
    // The docs appear to be wrong: explicit activation is not enforced by either NCLU or FRR, and
    // we have tested that route reflection works without it.
    boolean routeReflectorClient =
        Optional.ofNullable(ipv4UnicastAddressFamily)
            .map(af -> Boolean.TRUE.equals(af.getRouteReflectorClient()))
            .orElse(false);

    return Ipv4UnicastAddressFamily.builder()
        .setAddressFamilyCapabilities(
            AddressFamilyCapabilities.builder()
                .setSendCommunity(true)
                .setSendExtendedCommunity(true)
                .setAllowLocalAsIn(
                    (ipv4UnicastAddressFamily != null
                        && (firstNonNull(ipv4UnicastAddressFamily.getAllowAsIn(), 0) > 0)))
                .build())
        .setExportPolicy(exportRoutingPolicy.getName())
        .setImportPolicy(importRoutingPolicy == null ? null : importRoutingPolicy.getName())
        .setRouteReflectorClient(routeReflectorClient)
        .build();
  }

  private static void addIpv4BgpNeighbor(
      Configuration c,
      CumulusNodeConfiguration vsConfig,
      BgpIpNeighbor neighbor,
      @Nullable Long localAs,
      BgpVrf bgpVrf,
      org.batfish.datamodel.BgpProcess newProc,
      Warnings w) {
    if (neighbor.getPeerIp() == null
        || (neighbor.getRemoteAs() == null && neighbor.getRemoteAsType() == null)) {
      w.redFlag("Skipping invalidly configured BGP peer " + neighbor.getName());
      return;
    }

    BgpActivePeerConfig.Builder peerConfigBuilder =
        BgpActivePeerConfig.builder()
            .setLocalIp(
                Optional.ofNullable(
                        resolveLocalIpFromUpdateSource(neighbor.getBgpNeighborSource(), c, w))
                    .orElse(
                        computeLocalIpForBgpNeighbor(neighbor.getPeerIp(), c, bgpVrf.getVrfName())))
            .setPeerAddress(neighbor.getPeerIp());
    generateBgpCommonPeerConfig(c, vsConfig, neighbor, localAs, bgpVrf, newProc, peerConfigBuilder);
  }

  @Nonnull
  private static RoutingPolicy computeBgpNeighborExportRoutingPolicy(
      Configuration c, BgpNeighbor neighbor, BgpVrf bgpVrf) {
    String vrfName = bgpVrf.getVrfName();

    RoutingPolicy.Builder peerExportPolicy =
        RoutingPolicy.builder()
            .setOwner(c)
            .setName(computeBgpPeerExportPolicyName(vrfName, neighbor.getName()));

    if (bgpDefaultOriginate(neighbor)) {
      initBgpDefaultRouteExportPolicy(vrfName, neighbor.getName(), true, null, c);
      peerExportPolicy.addStatement(
          new If(
              "Export default route from peer with default-originate configured",
              new CallExpr(
                  computeBgpDefaultRouteExportPolicyName(true, vrfName, neighbor.getName())),
              singletonList(Statements.ReturnTrue.toStaticStatement()),
              ImmutableList.of()));
    }
    // FRR does not advertise default routes even if they are in the routing table:
    // https://readthedocs.org/projects/frrouting/downloads/pdf/stable-5.0/
    peerExportPolicy.addStatement(REJECT_DEFAULT_ROUTE);

    BooleanExpr peerExportConditions = computePeerExportConditions(neighbor, bgpVrf);
    List<Statement> acceptStmts = getAcceptStatements(neighbor, bgpVrf);

    peerExportPolicy.addStatement(
        new If(
            "peer-export policy main conditional: exitAccept if true / exitReject if false",
            peerExportConditions,
            acceptStmts,
            ImmutableList.of(Statements.ExitReject.toStaticStatement())));

    return peerExportPolicy.build();
  }

  /**
   * Initializes export policy for IPv4 or IPv6 default routes if it doesn't already exist. This
   * policy is the same across BGP processes, so only one is created for each configuration.
   *
   * @param ipv4 Whether to initialize the IPv4 or IPv6 default route export policy
   * @param defaultOriginateExportMapName Name of route-map to apply to generated route before
   *     export.
   */
  // TODO: This function is copied verbatim from CiscoConversations. Refactor after we've verified
  // the right behavior for default-originate.
  private static void initBgpDefaultRouteExportPolicy(
      String vrfName,
      String peerName,
      boolean ipv4,
      @Nullable String defaultOriginateExportMapName,
      Configuration c) {
    SetOrigin setOrigin =
        new SetOrigin(
            new LiteralOrigin(
                c.getConfigurationFormat() == ConfigurationFormat.CISCO_IOS
                    ? OriginType.IGP
                    : OriginType.INCOMPLETE,
                null));
    List<Statement> defaultRouteExportStatements;
    if (defaultOriginateExportMapName == null
        || !c.getRoutingPolicies().keySet().contains(defaultOriginateExportMapName)) {
      defaultRouteExportStatements =
          ImmutableList.of(setOrigin, Statements.ReturnTrue.toStaticStatement());
    } else {
      defaultRouteExportStatements =
          ImmutableList.of(
              setOrigin,
              new CallStatement(defaultOriginateExportMapName),
              Statements.ReturnTrue.toStaticStatement());
    }

    RoutingPolicy.builder()
        .setOwner(c)
        .setName(computeBgpDefaultRouteExportPolicyName(ipv4, vrfName, peerName))
        .addStatement(
            new If(
                new Conjunction(
                    ImmutableList.of(
                        ipv4 ? Common.matchDefaultRoute() : Common.matchDefaultRouteV6(),
                        new MatchProtocol(RoutingProtocol.AGGREGATE))),
                defaultRouteExportStatements))
        .addStatement(Statements.ReturnFalse.toStaticStatement())
        .build();
  }

  @Nullable
  @VisibleForTesting
  static RoutingPolicy computeBgpNeighborImportRoutingPolicy(
      Configuration c, BgpNeighbor neighbor, BgpVrf bgpVrf) {
    BooleanExpr peerImportConditions = getBgpNeighborImportPolicyCallExpr(neighbor);
    if (peerImportConditions == null) {
      return null;
    }

    String vrfName = bgpVrf.getVrfName();

    RoutingPolicy.Builder peerImportPolicy =
        RoutingPolicy.builder()
            .setOwner(c)
            .setName(computeBgpPeerImportPolicyName(vrfName, neighbor.getName()));

    peerImportPolicy.addStatement(
        new If(
            "peer-import policy main conditional: exitAccept if true / exitReject if false",
            peerImportConditions,
            ImmutableList.of(Statements.ExitAccept.toStaticStatement()),
            ImmutableList.of(Statements.ExitReject.toStaticStatement())));

    return peerImportPolicy.build();
  }

  private static BooleanExpr computePeerExportConditions(BgpNeighbor neighbor, BgpVrf bgpVrf) {
    BooleanExpr commonCondition =
        new CallExpr(computeBgpCommonExportPolicyName(bgpVrf.getVrfName()));
    BooleanExpr peerCondition = getBgpNeighborExportPolicyCallExpr(neighbor);

    return peerCondition == null
        ? commonCondition
        : new Conjunction(ImmutableList.of(commonCondition, peerCondition));
  }

  private static List<Statement> getAcceptStatements(BgpNeighbor neighbor, BgpVrf bgpVrf) {
    SetNextHop setNextHop = getSetNextHop(neighbor, bgpVrf);
    return setNextHop == null
        ? ImmutableList.of(Statements.ExitAccept.toStaticStatement())
        : ImmutableList.of(setNextHop, Statements.ExitAccept.toStaticStatement());
  }

  private static @Nullable CallExpr getBgpNeighborExportPolicyCallExpr(BgpNeighbor neighbor) {
    return Optional.ofNullable(neighbor.getIpv4UnicastAddressFamily())
        .map(BgpNeighborIpv4UnicastAddressFamily::getRouteMapOut)
        .map(CallExpr::new)
        .orElse(null);
  }

  private static @Nullable CallExpr getBgpNeighborImportPolicyCallExpr(BgpNeighbor neighbor) {
    return Optional.ofNullable(neighbor.getIpv4UnicastAddressFamily())
        .map(BgpNeighborIpv4UnicastAddressFamily::getRouteMapIn)
        .map(CallExpr::new)
        .orElse(null);
  }

  @VisibleForTesting
  static @Nullable SetNextHop getSetNextHop(BgpNeighbor neighbor, BgpVrf bgpVrf) {
    if (neighbor.getRemoteAs() == null
        || bgpVrf.getAutonomousSystem() == null
        || !neighbor.getRemoteAs().equals(bgpVrf.getAutonomousSystem())) {
      return null;
    }

    boolean nextHopSelf =
        Optional.ofNullable(neighbor.getIpv4UnicastAddressFamily())
            .map(BgpNeighborIpv4UnicastAddressFamily::getNextHopSelf)
            .orElse(false);

    return nextHopSelf ? new SetNextHop(SelfNextHop.getInstance()) : null;
  }

  @Nullable
  @VisibleForTesting
  static Ip resolveLocalIpFromUpdateSource(
      @Nullable BgpNeighborSource source, Configuration c, Warnings warnings) {
    if (source == null) {
      return null;
    }

    BgpNeighborSourceVisitor<Ip> visitor =
        new BgpNeighborSourceVisitor<Ip>() {

          @Override
          public Ip visitBgpNeighborSourceAddress(BgpNeighborSourceAddress updateSourceAddress) {
            return updateSourceAddress.getAddress();
          }

          @Nullable
          @Override
          public Ip visitBgpNeighborSourceInterface(
              BgpNeighborSourceInterface updateSourceInterface) {
            org.batfish.datamodel.Interface iface =
                c.getAllInterfaces().get(updateSourceInterface.getInterface());

            if (iface == null) {
              warnings.redFlag(
                  String.format(
                      "cannot find interface named %s for update-source",
                      updateSourceInterface.getInterface()));
              return null;
            }

            ConcreteInterfaceAddress concreteAddress = iface.getConcreteAddress();
            if (concreteAddress == null) {
              warnings.redFlag(
                  String.format(
                      "cannot find an address for interface named %s for update-source",
                      updateSourceInterface.getInterface()));
              return null;
            }

            return iface.getConcreteAddress().getIp();
          }
        };

    return source.accept(visitor);
  }

  /** Scan all interfaces, find first that contains given remote IP */
  @Nullable
  @VisibleForTesting
  static Ip computeLocalIpForBgpNeighbor(Ip remoteIp, Configuration c, String vrfName) {
    org.batfish.datamodel.Vrf vrf = c.getVrfs().get(vrfName);
    if (vrf == null) {
      return null;
    }
    return c.getAllInterfaces(vrf.getName()).values().stream()
        .flatMap(
            i ->
                i.getAllConcreteAddresses().stream()
                    .filter(
                        addr ->
                            addr.getPrefix().containsIp(remoteIp)
                                && !addr.getIp().equals(remoteIp)))
        .findFirst()
        .map(ConcreteInterfaceAddress::getIp)
        .orElse(null);
  }

  /**
   * Create common BGP export policy. This policy permits:
   *
   * <ul>
   *   <li>BGP and iBGP routes
   *   <li>routes whose network matches a configured network statement
   *   <li>routes whose protocol matches a configured protocol
   *   <li>redistribution policy
   * </ul>
   *
   * <p>all other routes are denied.
   */
  private static void generateBgpCommonExportPolicy(
      Configuration c, String vrfName, BgpVrf bgpVrf, Map<String, RouteMap> routeMaps) {
    RoutingPolicy bgpCommonExportPolicy =
        RoutingPolicy.builder()
            .setOwner(c)
            .setName(computeBgpCommonExportPolicyName(vrfName))
            .build();

    List<Statement> statements = new ArrayList<>();

    // 1. If there are any ipv4 summary only networks, do not export the more specific routes.
    if (bgpVrf.getIpv4Unicast() != null) {
      Stream<Prefix> summarizedPrefixes =
          bgpVrf.getIpv4Unicast().getAggregateNetworks().entrySet().stream()
              .filter(e -> e.getValue().isSummaryOnly())
              .map(Entry::getKey);
      Optional.ofNullable(suppressSummarizedPrefixes(c, vrfName, summarizedPrefixes))
          .ifPresent(statements::add);
    }

    // 2. Setup export conditions, export if match, otherwise fall through
    Disjunction exportConditions = new Disjunction();

    // 2a. add export conditions for non-aggregate routes
    exportConditions.getDisjuncts().addAll(getBgpExportConditions(bgpVrf, routeMaps));

    // 2b. add export conditions for aggregate routes
    if (bgpVrf.getIpv4Unicast() != null) {
      exportConditions
          .getDisjuncts()
          .add(generateExportAggregateConditions(bgpVrf.getIpv4Unicast().getAggregateNetworks()));
    }

    statements.add(
        new If(
            exportConditions,
            ImmutableList.of(Statements.ReturnTrue.toStaticStatement()),
            ImmutableList.of(Statements.ReturnFalse.toStaticStatement())));

    bgpCommonExportPolicy.setStatements(statements);
  }

  private static List<BooleanExpr> getBgpExportConditions(
      BgpVrf bgpVrf, Map<String, RouteMap> routeMaps) {
    List<BooleanExpr> exportConditions = new ArrayList<>();

    // Always export BGP and iBGP routes
    exportConditions.add(new MatchProtocol(RoutingProtocol.BGP, RoutingProtocol.IBGP));

    // If no IPv4 address family is not defined, there is no capability to explicitly advertise v4
    // networks or redistribute protocols, so no non-BGP routes can be exported.
    if (bgpVrf.getIpv4Unicast() == null) {
      return exportConditions;
    }

    // Add conditions to redistribute other protocols
    for (BgpRedistributionPolicy redistributeProtocolPolicy :
        bgpVrf.getIpv4Unicast().getRedistributionPolicies().values()) {

      // Get a match expression for the protocol to be redistributed
      CumulusRoutingProtocol protocol = redistributeProtocolPolicy.getProtocol();
      MatchProtocol matchProtocol = new MatchProtocol(VI_PROTOCOLS_MAP.get(protocol));

      // Create a WithEnvironmentExpr with the redistribution route-map, if one is defined
      BooleanExpr weInterior = BooleanExprs.TRUE;
      String mapName = redistributeProtocolPolicy.getRouteMap();
      if (mapName != null && routeMaps.keySet().contains(mapName)) {
        weInterior = new CallExpr(mapName);
      }
      BooleanExpr we = bgpRedistributeWithEnvironmentExpr(weInterior, OriginType.INCOMPLETE);

      // Export routes that match the protocol and WithEnvironmentExpr
      Conjunction exportProtocolConditions = new Conjunction(ImmutableList.of(matchProtocol, we));
      exportProtocolConditions.setComment(
          String.format("Redistribute %s routes into BGP", protocol));
      exportConditions.add(exportProtocolConditions);
    }

    // create origination prefilter from listed advertised networks
    bgpVrf
        .getIpv4Unicast()
        .getNetworks()
        .forEach(
            (prefix, bgpNetwork) -> {
              BooleanExpr weExpr = BooleanExprs.TRUE;
              BooleanExpr we = bgpRedistributeWithEnvironmentExpr(weExpr, OriginType.IGP);
              Conjunction exportNetworkConditions = new Conjunction();
              exportNetworkConditions
                  .getConjuncts()
                  .add(
                      new MatchPrefixSet(
                          DestinationNetwork.instance(),
                          new ExplicitPrefixSet(new PrefixSpace(PrefixRange.fromPrefix(prefix)))));
              /*
              Don't need to explicitly exclude BGP and iBGP routes here because those routes will
              already be matched earlier in exportConditions (which are disjuncts).
               */
              exportNetworkConditions
                  .getConjuncts()
                  .add(new Not(new MatchProtocol(RoutingProtocol.AGGREGATE)));
              exportNetworkConditions.getConjuncts().add(we);
              exportConditions.add(exportNetworkConditions);
            });
    return exportConditions;
  }

  /** Returns whether we originate default toward this neighbor */
  private static boolean bgpDefaultOriginate(BgpNeighbor neighbor) {
    return neighbor.getIpv4UnicastAddressFamily() != null
        && Boolean.TRUE.equals(neighbor.getIpv4UnicastAddressFamily().getDefaultOriginate());
  }

  @Nonnull
  private static LongSpace computeRemoteAsns(BgpNeighbor neighbor, @Nullable Long localAs) {
    if (neighbor.getRemoteAsType() == RemoteAsType.EXPLICIT) {
      Long remoteAs = neighbor.getRemoteAs();
      return remoteAs == null ? LongSpace.EMPTY : LongSpace.of(remoteAs);
    } else if (localAs == null) {
      return LongSpace.EMPTY;
    } else if (neighbor.getRemoteAsType() == RemoteAsType.EXTERNAL) {
      return BgpPeerConfig.ALL_AS_NUMBERS.difference(LongSpace.of(localAs));
    } else if (neighbor.getRemoteAsType() == RemoteAsType.INTERNAL) {
      return LongSpace.of(localAs);
    }
    throw new IllegalArgumentException(
        String.format("Invalid remote-as type: %s", neighbor.getRemoteAsType()));
  }

  @Nullable
  private static EvpnAddressFamily toEvpnAddressFamily(
      Configuration c,
      CumulusNodeConfiguration vsConfig,
      BgpNeighbor neighbor,
      @Nullable Long localAs,
      BgpVrf bgpVrf,
      org.batfish.datamodel.BgpProcess newProc,
      RoutingPolicy routingPolicy) {
    BgpL2vpnEvpnAddressFamily evpnConfig = bgpVrf.getL2VpnEvpn();
    // sadly, we allow localAs == null in VI datamodel
    if (evpnConfig == null
        || localAs == null
        || neighbor.getL2vpnEvpnAddressFamily() == null
        // l2vpn evpn AF must be explicitly activated for neighbor
        || !firstNonNull(neighbor.getL2vpnEvpnAddressFamily().getActivated(), Boolean.FALSE)) {
      return null;
    }
    ImmutableSet.Builder<Layer2VniConfig> l2Vnis = ImmutableSet.builder();
    ImmutableSet.Builder<Layer3VniConfig> l3Vnis = ImmutableSet.builder();
    ImmutableMap.Builder<Integer, Integer> vniToIndexBuilder = ImmutableMap.builder();
    CommonUtil.forEachWithIndex(
        // Keep indices in deterministic order
        ImmutableList.sortedCopyOf(
            Comparator.nullsLast(Comparator.comparing(Vxlan::getId)),
            vsConfig.getVxlans().values()),
        (index, vxlan) -> {
          if (vxlan.getId() == null) {
            return;
          }
          vniToIndexBuilder.put(vxlan.getId(), index);
        });
    Map<Integer, Integer> vniToIndex = vniToIndexBuilder.build();

    if (evpnConfig.getAdvertiseAllVni()) {
      for (Layer2Vni vxlan : c.getVrfs().get(bgpVrf.getVrfName()).getLayer2Vnis().values()) {
        RouteDistinguisher rd =
            RouteDistinguisher.from(newProc.getRouterId(), vniToIndex.get(vxlan.getVni()));
        ExtendedCommunity rt = toRouteTarget(localAs, vxlan.getVni());
        // Advertise L2 VNIs
        l2Vnis.add(
            Layer2VniConfig.builder()
                .setVni(vxlan.getVni())
                .setVrf(bgpVrf.getVrfName())
                .setRouteDistinguisher(rd)
                .setRouteTarget(rt)
                .build());
      }
    }
    BgpProcess bgpProcess = vsConfig.getBgpProcess();
    // Advertise the L3 VNI per vrf if one is configured
    assert bgpProcess != null; // Since we are in neighbor conversion, this must be true
    // Iterate over ALL vrfs, because even if the vrf doesn't appear in bgp process config, we
    // must be aware of the fact that it has a VNI and advertise it.
    vsConfig
        .getVrfs()
        .values()
        .forEach(
            innerVrf -> {
              String innerVrfName = innerVrf.getName();
              Integer l3Vni = innerVrf.getVni();
              if (l3Vni == null) {
                return;
              }
              RouteDistinguisher rd =
                  RouteDistinguisher.from(
                      Optional.ofNullable(c.getVrfs().get(innerVrfName).getBgpProcess())
                          .map(org.batfish.datamodel.BgpProcess::getRouterId)
                          .orElse(bgpVrf.getRouterId()),
                      vniToIndex.get(l3Vni));
              ExtendedCommunity rt = toRouteTarget(localAs, l3Vni);
              // Grab the BgpVrf for the innerVrf, if it exists
              @Nullable
              BgpVrf innerBgpVrf =
                  (innerVrfName.equals(DEFAULT_VRF_NAME)
                      ? bgpProcess.getDefaultVrf()
                      : bgpProcess.getVrfs().get(innerVrfName));
              l3Vnis.add(
                  Layer3VniConfig.builder()
                      .setVni(l3Vni)
                      .setVrf(innerVrfName)
                      .setRouteDistinguisher(rd)
                      .setRouteTarget(rt)
                      .setImportRouteTarget(importRtPatternForAnyAs(l3Vni))
                      .setAdvertiseV4Unicast(
                          Optional.ofNullable(innerBgpVrf)
                              .map(BgpVrf::getL2VpnEvpn)
                              .map(BgpL2vpnEvpnAddressFamily::getAdvertiseIpv4Unicast)
                              .isPresent())
                      .build());
            });

    return EvpnAddressFamily.builder()
        .setL2Vnis(l2Vnis.build())
        .setL3Vnis(l3Vnis.build())
        .setPropagateUnmatched(true)
        .setAddressFamilyCapabilities(
            AddressFamilyCapabilities.builder()
                .setSendCommunity(true)
                .setSendExtendedCommunity(true)
                .build())
        .setRouteReflectorClient(
            firstNonNull(
                neighbor.getL2vpnEvpnAddressFamily().getRouteReflectorClient(), Boolean.FALSE))
        .setExportPolicy(routingPolicy.getName())
        .build();
  }

  /**
   * Convert AS number and VXLAN ID to an extended route target community. If the AS number is a
   * 4-byte as, only the lower 2 bytes are used.
   *
   * <p>See <a
   * href="https://docs.cumulusnetworks.com/display/DOCS/Ethernet+Virtual+Private+Network+-+EVPN#EthernetVirtualPrivateNetwork-EVPN-RD-auto-derivationAuto-derivationofRDsandRTs">
   * cumulus documentation</a> for detailed explanation.
   */
  @Nonnull
  private static ExtendedCommunity toRouteTarget(long asn, long vxlanId) {
    return ExtendedCommunity.target(asn & 0xFFFFL, vxlanId);
  }

  static void convertOspfProcess(Configuration c, CumulusNodeConfiguration vsConfig, Warnings w) {
    @Nullable OspfProcess ospfProcess = vsConfig.getOspfProcess();
    if (ospfProcess == null) {
      return;
    }

    convertOspfVrf(
        c,
        ospfProcess.getDefaultVrf(),
        c.getDefaultVrf(),
        vsConfig.getLoopback(),
        vsConfig.getInterfaces(),
        w);

    ospfProcess
        .getVrfs()
        .values()
        .forEach(
            ospfVrf -> {
              org.batfish.datamodel.Vrf vrf = c.getVrfs().get(ospfVrf.getVrfName());

              if (vrf == null) {
                w.redFlag(String.format("Vrf %s is not found.", ospfVrf.getVrfName()));
                return;
              }

              convertOspfVrf(c, ospfVrf, vrf, vsConfig.getLoopback(), vsConfig.getInterfaces(), w);
            });
  }

  private static void convertOspfVrf(
      Configuration c,
      OspfVrf ospfVrf,
      org.batfish.datamodel.Vrf vrf,
      Loopback loopback,
      Map<String, Interface> vsInterfaces,
      Warnings w) {
    org.batfish.datamodel.ospf.OspfProcess ospfProcess =
        toOspfProcess(ospfVrf, c.getAllInterfaces(vrf.getName()), loopback, vsInterfaces, w);
    vrf.addOspfProcess(ospfProcess);
  }

  @VisibleForTesting
  static org.batfish.datamodel.ospf.OspfProcess toOspfProcess(
      OspfVrf ospfVrf,
      Map<String, org.batfish.datamodel.Interface> vrfInterfaces,
      Loopback loopback,
      Map<String, Interface> vsInterfaces,
      Warnings w) {
    Ip routerId = ospfVrf.getRouterId();
    if (routerId == null) {
      routerId = inferRouterId(loopback, vsInterfaces);
    }

    org.batfish.datamodel.ospf.OspfProcess.Builder builder =
        org.batfish.datamodel.ospf.OspfProcess.builder();

    org.batfish.datamodel.ospf.OspfProcess proc =
        builder
            .setRouterId(routerId)
            .setProcessId(DEFAULT_OSPF_PROCESS_NAME)
            .setReferenceBandwidth(OspfProcess.DEFAULT_REFERENCE_BANDWIDTH)
            .build();

    addOspfInterfaces(vrfInterfaces, proc.getProcessId(), vsInterfaces, w);
    proc.setAreas(computeOspfAreas(vrfInterfaces.keySet(), vsInterfaces));
    return proc;
  }

  /**
   * Logic of inferring router ID for Zebra based system
   * (https://github.com/coreswitch/zebra/blob/master/docs/router-id.md):
   *
   * <p>If the loopback is configured with an IP address NOT in 127.0.0.0/8, the numerically largest
   * such IP is used. Otherwise, the numerically largest IP configured on any interface on the
   * device is used. Otherwise, 0.0.0.0 is used.
   */
  @VisibleForTesting
  static Ip inferRouterId(Loopback loopback, Map<String, Interface> vsInterfaces) {
    if (loopback.getConfigured()) {
      Optional<ConcreteInterfaceAddress> maxLoIp =
          loopback.getAddresses().stream()
              .filter(addr -> !LOOPBACK_PREFIX.containsIp(addr.getIp()))
              .max(ConcreteInterfaceAddress::compareTo);
      if (maxLoIp.isPresent()) {
        return maxLoIp.get().getIp();
      }
    }

    Optional<ConcreteInterfaceAddress> biggestInterfaceIp =
        vsInterfaces.values().stream()
            .flatMap(iface -> iface.getIpAddresses().stream())
            .max(InterfaceAddress::compareTo);

    return biggestInterfaceIp
        .map(ConcreteInterfaceAddress::getIp)
        .orElseGet(() -> Ip.parse("0.0.0.0"));
  }

  @VisibleForTesting
  static void addOspfInterfaces(
      Map<String, org.batfish.datamodel.Interface> viIfaces,
      String processId,
      Map<String, Interface> vsIfaces,
      Warnings w) {
    viIfaces.forEach(
        (ifaceName, iface) -> {
          Interface vsIface = vsIfaces.get(iface.getName());
          if (vsIface == null) {
            // if this interface does not exist (e.g., it's a bond) we skip it for now
            // TODO: need to handle other types of interfaces: bonds, vlans, bridge
            return;
          }

          OspfInterface ospfInterface = vsIface.getOspf();
          if (ospfInterface == null || ospfInterface.getOspfArea() == null) {
            // no ospf running on this interface
            return;
          }

          iface.setOspfSettings(
              OspfInterfaceSettings.builder()
                  .setPassive(Optional.ofNullable(ospfInterface.getPassive()).orElse(false))
                  .setAreaName(ospfInterface.getOspfArea())
                  .setNetworkType(toOspfNetworkType(ospfInterface.getNetwork(), w))
                  .setDeadInterval(
                      Optional.ofNullable(ospfInterface.getDeadInterval())
                          .orElse(DEFAULT_OSPF_DEAD_INTERVAL))
                  .setHelloInterval(
                      Optional.ofNullable(ospfInterface.getHelloInterval())
                          .orElse(DEFAULT_OSPF_HELLO_INTERVAL))
                  .setProcess(processId)
                  .build());
        });
  }

  @VisibleForTesting
  static SortedMap<Long, OspfArea> computeOspfAreas(
      Collection<String> vrfIfaceNames, Map<String, Interface> vsIfaces) {
    Map<Long, List<String>> areaInterfaces =
        vrfIfaceNames.stream()
            .map(vsIfaces::get)
            .filter(
                vsIface ->
                    vsIface != null
                        && vsIface.getOspf() != null
                        && vsIface.getOspf().getOspfArea() != null)
            .collect(
                groupingBy(
                    vsIface -> vsIface.getOspf().getOspfArea(),
                    mapping(Interface::getName, Collectors.toList())));

    return toImmutableSortedMap(
        areaInterfaces,
        Entry::getKey,
        e -> OspfArea.builder().setNumber(e.getKey()).addInterfaces(e.getValue()).build());
  }

  @Nullable
  private static org.batfish.datamodel.ospf.OspfNetworkType toOspfNetworkType(
      @Nullable OspfNetworkType type, Warnings w) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case BROADCAST:
        return org.batfish.datamodel.ospf.OspfNetworkType.BROADCAST;
      case POINT_TO_POINT:
        return org.batfish.datamodel.ospf.OspfNetworkType.POINT_TO_POINT;
      default:
        w.redFlag(
            String.format(
                "Conversion of Cumulus FRR OSPF network type '%s' is not handled.",
                type.toString()));
        return null;
    }
  }

  static void convertIpCommunityLists(
      Configuration c, Map<String, IpCommunityList> ipCommunityLists) {
    ipCommunityLists.forEach(
        (name, list) -> c.getCommunityLists().put(name, toCommunityList(list)));
  }

  @VisibleForTesting
  static CommunityList toCommunityList(IpCommunityList list) {
    return list.accept(
        ipCommunityList ->
            new CommunityList(
                ipCommunityList.getName(),
                ipCommunityList.getCommunities().stream()
                    .map(LiteralCommunity::new)
                    .map(k -> new CommunityListLine(ipCommunityList.getAction(), k))
                    .collect(ImmutableList.toImmutableList()),
                false));
  }

  static void convertIpAsPathAccessLists(
      Configuration c, Map<String, IpAsPathAccessList> ipAsPathAccessLists) {
    ipAsPathAccessLists.forEach(
        (name, asPathAccessList) ->
            c.getAsPathAccessLists().put(name, toAsPathAccessList(asPathAccessList)));
  }

  @VisibleForTesting
  static @Nonnull AsPathAccessList toAsPathAccessList(IpAsPathAccessList asPathAccessList) {
    String name = asPathAccessList.getName();
    List<AsPathAccessListLine> lines =
        asPathAccessList.getLines().stream()
            // TODO Check FRR AS path match semantics.
            // This regex assumes we should match any path containing the specified ASN anywhere.
            .map(
                line ->
                    new AsPathAccessListLine(
                        line.getAction(), String.format("(^| )%s($| )", line.getAsNum())))
            .collect(ImmutableList.toImmutableList());
    return new AsPathAccessList(name, lines);
  }

  static void convertIpPrefixLists(Configuration c, Map<String, IpPrefixList> ipPrefixLists) {
    ipPrefixLists.forEach(
        (name, ipPrefixList) -> c.getRouteFilterLists().put(name, toRouteFilterList(ipPrefixList)));
  }

  @VisibleForTesting
  static @Nonnull RouteFilterList toRouteFilterList(IpPrefixList ipPrefixList) {
    String name = ipPrefixList.getName();
    RouteFilterList rfl = new RouteFilterList(name);
    rfl.setLines(
        ipPrefixList.getLines().values().stream()
            .map(CumulusConversions::toRouteFilterLine)
            .collect(ImmutableList.toImmutableList()));
    return rfl;
  }

  @VisibleForTesting
  static @Nonnull RouteFilterLine toRouteFilterLine(IpPrefixListLine ipPrefixListLine) {
    return new RouteFilterLine(
        ipPrefixListLine.getAction(),
        ipPrefixListLine.getPrefix(),
        ipPrefixListLine.getLengthRange());
  }

  static void convertRouteMaps(
      Configuration c, CumulusNodeConfiguration vc, Map<String, RouteMap> routeMaps, Warnings w) {
    routeMaps.forEach((name, routeMap) -> new RouteMapConvertor(c, vc, routeMap, w).toRouteMap());
  }

  static void convertDnsServers(Configuration c, List<Ip> ipv4Nameservers) {
    c.setDnsServers(
        ipv4Nameservers.stream()
            .map(Object::toString)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())));
  }
}
