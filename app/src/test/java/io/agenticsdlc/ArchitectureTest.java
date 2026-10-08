package io.agenticsdlc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hexagonal layering (ADR-0004): core depends only on the JDK and Reactor, and adapters talk to each
 * other only through core ports. Core rules are allowlists, so a new framework dependency fails here
 * instead of slipping through a denylist.
 */
@AnalyzeClasses(packages = "io.agenticsdlc", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule coreDependsOnlyOnJdkAndReactor = classes().that().resideInAPackage("io.agenticsdlc.core..")
			.should().onlyDependOnClassesThat().resideInAnyPackage(
					"java..", "reactor..", "org.reactivestreams..", "io.agenticsdlc.core..");

	@ArchTest
	static final ArchRule domainDependsOnlyOnJdk = classes().that().resideInAPackage("io.agenticsdlc.core.domain..")
			.should().onlyDependOnClassesThat().resideInAnyPackage("java..", "io.agenticsdlc.core.domain..");

	@ArchTest
	static final ArchRule coreIsFreeOfCycles = slices().matching("io.agenticsdlc.core.(*)..")
			.should().beFreeOfCycles();

	@ArchTest
	static final ArchRule inboundAdaptersDoNotReachOutboundAdapters = noClasses()
			.that().resideInAPackage("io.agenticsdlc.adapter.in..")
			.should().dependOnClassesThat().resideInAPackage("io.agenticsdlc.adapter.out..");

	@ArchTest
	static final ArchRule outboundAdaptersAreIndependent = slices()
			.matching("io.agenticsdlc.adapter.out.(*)..")
			.should().notDependOnEachOther();

	@ArchTest
	static final ArchRule outboundAdaptersDoNotReachInbound = noClasses()
			.that().resideInAPackage("io.agenticsdlc.adapter.out..")
			.should().dependOnClassesThat().resideInAPackage("io.agenticsdlc.adapter.in..");

	/** ADR-0003, Rule of Two: what the model drives can never push, open pull requests or write to tickets. */
	@ArchTest
	static final ArchRule agentsCannotReachPublishing = noClasses()
			.that().resideInAnyPackage("io.agenticsdlc.core.agent..", "io.agenticsdlc.core.stage..")
			.should().dependOnClassesThat().resideInAnyPackage("io.agenticsdlc.core.scm..", "io.agenticsdlc.core.intake..");

	@ArchTest
	static final ArchRule modelAdaptersCannotReachScm = noClasses()
			.that().resideInAPackage("io.agenticsdlc.adapter.out.llm..")
			.should().dependOnClassesThat().resideInAnyPackage("io.agenticsdlc.adapter.out.scm..",
					"io.agenticsdlc.adapter.out.git..", "io.agenticsdlc.core.scm..");

	@ArchTest
	static final ArchRule productionCodeLivesInKnownLayers = classes()
			.that().resideOutsideOfPackages("io.agenticsdlc.core..", "io.agenticsdlc.adapter..",
					"io.agenticsdlc.config..")
			.should().haveSimpleName("AgenticSdlcApplication");
}
