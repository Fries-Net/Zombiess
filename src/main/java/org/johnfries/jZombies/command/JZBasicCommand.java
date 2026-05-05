package org.johnfries.jZombies.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public final class JZBasicCommand implements BasicCommand {
    private final JZCommand delegate;

    public JZBasicCommand(JZCommand delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        delegate.execute(source.getSender(), args);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return delegate.suggest(source.getSender(), args);
    }

    @Override
    public @Nullable String permission() {
        return delegate.permission();
    }
}
