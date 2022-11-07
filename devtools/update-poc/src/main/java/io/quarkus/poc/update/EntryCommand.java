package io.quarkus.poc.update;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { InfoCommand.class, UpdateCommand.class })
public class EntryCommand {
}
