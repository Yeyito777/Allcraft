package net.minecraft.core;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.ImmutableMap.Builder;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.minecraft.allcraft.AllcraftRegistries;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

public class MappedRegistry<T> implements WritableRegistry<T> {
   private final ResourceKey<? extends Registry<T>> key;
   private final ObjectList<Holder.Reference<T>> byId = new ObjectArrayList(256);
   private final Reference2IntMap<T> toId = Util.make(new Reference2IntOpenHashMap(), t -> t.defaultReturnValue(-1));
   private final Map<Identifier, Holder.Reference<T>> byLocation = new HashMap<>();
   private final Map<ResourceKey<T>, Holder.Reference<T>> byKey = new HashMap<>();
   private final Map<T, Holder.Reference<T>> byValue = new IdentityHashMap<>();
   private final Map<ResourceKey<T>, RegistrationInfo> registrationInfos = new IdentityHashMap<>();
   private Lifecycle registryLifecycle;
   private final Map<TagKey<T>, HolderSet.Named<T>> frozenTags = new IdentityHashMap<>();
   private final Set<ResourceKey<T>> allcraftRetired = Collections.newSetFromMap(new IdentityHashMap<>());
   private MappedRegistry.TagSet<T> allTags = MappedRegistry.TagSet.unbound();
   private @Nullable DataComponentLookup<T> componentLookup;
   private boolean frozen;
   private final boolean intrusiveHolders;
   private @Nullable Map<T, Holder.Reference<T>> unregisteredIntrusiveHolders;

   @Override
   public Stream<HolderSet.Named<T>> listTags() {
      return this.getTags();
   }

   public MappedRegistry(final ResourceKey<? extends Registry<T>> key, final Lifecycle lifecycle) {
      this(key, lifecycle, false);
   }

   public MappedRegistry(final ResourceKey<? extends Registry<T>> key, final Lifecycle initialLifecycle, final boolean intrusiveHolders) {
      this.key = key;
      this.registryLifecycle = initialLifecycle;
      this.intrusiveHolders = intrusiveHolders;
      if (intrusiveHolders) {
         this.unregisteredIntrusiveHolders = new IdentityHashMap<>();
      }
   }

   @Override
   public ResourceKey<? extends Registry<T>> key() {
      return this.key;
   }

   @Override
   public String toString() {
      return "Registry[" + this.key + " (" + this.registryLifecycle + ")]";
   }

   private void validateWrite() {
      if (this.frozen && !AllcraftRegistries.mutationAllowed()) {
         throw new IllegalStateException("Registry is already frozen");
      }
   }

   private void validateWrite(final ResourceKey<T> key) {
      if (this.frozen && !AllcraftRegistries.mutationAllowed()) {
         throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
      }
   }

   @Override
   public synchronized Holder.Reference<T> register(final ResourceKey<T> key, final T value, final RegistrationInfo registrationInfo) {
      this.validateWrite(key);
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      Holder.Reference<T> existing = this.byLocation.get(key.identifier());
      if (existing != null && this.frozen) {
         int existingId = this.toId.getInt(existing.value());
         int assignedId = AllcraftRegistries.claim(this, key, existingId, "ensure");
         if (assignedId != existingId) {
            throw new IllegalStateException("Registry plan assigned " + key + " ID " + assignedId + " but it is already ID " + existingId);
         }
         Holder.Reference<T> intrusive = this.takeIntrusiveHolder(value, false);
         if (intrusive != null && intrusive != existing) {
            intrusive.allcraftAlias(existing);
         }
         this.allcraftRetired.remove(key);
         AllcraftRegistries.onEnsured(key, existing.value());
         return existing;
      }
      if (existing != null) {
         throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate key '" + key + "' to registry"));
      }

      if (this.byValue.containsKey(value)) {
         throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate value '" + value + "' to registry"));
      }

      Holder.Reference<T> holder;
      if (this.intrusiveHolders) {
         holder = this.takeIntrusiveHolder(value, true);
         if (holder == null) {
            throw new AssertionError("Missing intrusive holder for " + key + ":" + value);
         }
         holder.bindKey(key);
      } else {
         holder = this.byKey.get(key);
         if (holder == null) {
            holder = Holder.Reference.createStandAlone(this, key);
         }
      }

      boolean runtimeMutation = this.frozen;
      int proposedId = this.allcraftNextId();
      int newId = runtimeMutation ? AllcraftRegistries.claim(this, key, proposedId, "ensure") : this.byId.size();
      if (newId < 0) {
         throw new IllegalStateException("Negative registry ID " + newId + " for " + key);
      }
      while (this.byId.size() <= newId) {
         this.byId.add(null);
      }
      if (this.byId.get(newId) != null) {
         throw new IllegalStateException("Registry ID " + newId + " is already occupied while adding " + key);
      }
      this.byKey.put(key, holder);
      this.byLocation.put(key.identifier(), holder);
      this.byValue.put(value, holder);
      this.byId.set(newId, holder);
      this.toId.put(value, newId);
      this.registrationInfos.put(key, registrationInfo);
      Lifecycle previousLifecycle = this.registryLifecycle;
      this.registryLifecycle = this.registryLifecycle.add(registrationInfo.lifecycle());
      if (runtimeMutation) {
         Holder.Reference<T> registeredHolder = holder;
         holder.bindValue(value);
         holder.bindTags(List.of());
         holder.bindComponents(DataComponentMap.EMPTY);
         this.componentLookup = new DataComponentLookup<>(this.byId);
         AllcraftRegistries.recordRetainedUndo(
            "remove runtime registry entry " + key,
            () -> this.allcraftUndoAdd(key, value, registeredHolder, newId, previousLifecycle),
            () -> this.allcraftRetired.add(key)
         );
         AllcraftRegistries.onRegistered(this, key, value, false);
      }
      return holder;
   }

   private Holder.@Nullable Reference<T> takeIntrusiveHolder(final T value, final boolean required) {
      if (!this.intrusiveHolders) {
         return null;
      }
      if (this.unregisteredIntrusiveHolders == null) {
         if (required) {
            throw new AssertionError("Missing intrusive-holder table for " + this.key);
         }
         return null;
      }
      return this.unregisteredIntrusiveHolders.remove(value);
   }

   public synchronized int allcraftNextId() {
      for (int id = 0; id < this.byId.size(); id++) {
         if (this.byId.get(id) == null) {
            return id;
         }
      }
      return this.byId.size();
   }

   public synchronized T allcraftReplace(final ResourceKey<T> key, final T value, final RegistrationInfo registrationInfo) {
      this.validateWrite(key);
      Holder.Reference<T> holder = this.byKey.get(key);
      if (holder == null) {
         return this.register(key, value, registrationInfo).value();
      }
      T previousValue = holder.value();
      int id = this.toId.getInt(previousValue);
      int assignedId = AllcraftRegistries.claim(this, key, id, "replace");
      if (assignedId != id) {
         throw new IllegalStateException("Replacement for " + key + " must retain ID " + id + ", not " + assignedId);
      }
      Holder.Reference<T> intrusive = this.takeIntrusiveHolder(value, false);
      if (intrusive != null && intrusive != holder) {
         intrusive.allcraftAlias(holder);
      }
      RegistrationInfo previousInfo = this.registrationInfos.get(key);
      Lifecycle previousLifecycle = this.registryLifecycle;
      boolean wasRetired = this.allcraftRetired.remove(key);
      this.byValue.remove(previousValue);
      this.toId.removeInt(previousValue);
      holder.bindValue(value);
      this.byValue.put(value, holder);
      this.toId.put(value, id);
      this.registrationInfos.put(key, registrationInfo);
      this.registryLifecycle = this.registryLifecycle.add(registrationInfo.lifecycle());
      this.componentLookup = new DataComponentLookup<>(this.byId);
      AllcraftRegistries.recordUndo(
         "restore runtime registry replacement " + key,
         () -> this.allcraftUndoReplace(key, holder, value, previousValue, id, previousInfo, previousLifecycle, wasRetired)
      );
      AllcraftRegistries.onRegistered(this, key, value, true);
      return value;
   }

   public synchronized T allcraftRemove(final ResourceKey<T> key) {
      this.validateWrite(key);
      Holder.Reference<T> holder = this.byKey.get(key);
      if (holder == null) {
         throw new IllegalStateException("Cannot remove missing registry key " + key);
      }
      T value = holder.value();
      int id = this.toId.getInt(value);
      int assignedId = AllcraftRegistries.claim(this, key, id, "remove");
      if (assignedId != id) {
         throw new IllegalStateException("Removal for " + key + " expected ID " + id + ", not " + assignedId);
      }
      RegistrationInfo info = this.registrationInfos.remove(key);
      Lifecycle previousLifecycle = this.registryLifecycle;
      boolean wasRetired = this.allcraftRetired.remove(key);
      this.byKey.remove(key);
      this.byLocation.remove(key.identifier());
      this.byValue.remove(value);
      this.toId.removeInt(value);
      this.byId.set(id, null);
      this.componentLookup = new DataComponentLookup<>(this.byId);
      AllcraftRegistries.recordUndo(
         "restore removed runtime registry entry " + key,
         () -> this.allcraftUndoRemove(key, holder, value, id, info, previousLifecycle, wasRetired)
      );
      return value;
   }

   public synchronized void allcraftRetire(final ResourceKey<T> key) {
      this.validateWrite(key);
      Holder.Reference<T> holder = this.byKey.get(key);
      if (holder == null) {
         throw new IllegalStateException("Cannot retire missing registry key " + key);
      }
      int id = this.toId.getInt(holder.value());
      int assignedId = AllcraftRegistries.claim(this, key, id, "retire");
      if (assignedId != id) {
         throw new IllegalStateException("Retirement for " + key + " expected ID " + id + ", not " + assignedId);
      }
      if (this.allcraftRetired.add(key)) {
         AllcraftRegistries.recordUndo("reactivate runtime registry entry " + key, () -> this.allcraftRetired.remove(key));
      }
   }

   public synchronized void allcraftReactivate(final ResourceKey<T> key) {
      this.validateWrite(key);
      Holder.Reference<T> holder = this.byKey.get(key);
      if (holder == null) {
         throw new IllegalStateException("Cannot reactivate missing registry key " + key);
      }
      int id = this.toId.getInt(holder.value());
      int assignedId = AllcraftRegistries.claim(this, key, id, "reactivate");
      if (assignedId != id) {
         throw new IllegalStateException("Reactivation for " + key + " expected ID " + id + ", not " + assignedId);
      }
      this.allcraftEnsureActive(key);
   }

   /** Reactivates an existing process-lifetime tombstone after its ensure plan entry was consumed. */
   public synchronized void allcraftEnsureActive(final ResourceKey<T> key) {
      this.validateWrite(key);
      if (this.allcraftRetired.remove(key)) {
         AllcraftRegistries.recordUndo("retire runtime registry entry " + key, () -> this.allcraftRetired.add(key));
      }
   }

   public synchronized boolean allcraftIsRetired(final ResourceKey<?> key) {
      return this.allcraftRetired.contains(key);
   }

   private synchronized void allcraftUndoAdd(
      ResourceKey<T> key, T value, Holder.Reference<T> holder, int id, Lifecycle previousLifecycle
   ) {
      this.byKey.remove(key, holder);
      this.byLocation.remove(key.identifier(), holder);
      this.byValue.remove(value, holder);
      this.toId.removeInt(value);
      if (id < this.byId.size() && this.byId.get(id) == holder) {
         this.byId.set(id, null);
      }
      while (!this.byId.isEmpty() && this.byId.get(this.byId.size() - 1) == null) {
         this.byId.remove(this.byId.size() - 1);
      }
      this.registrationInfos.remove(key);
      this.allcraftRetired.remove(key);
      this.registryLifecycle = previousLifecycle;
      this.componentLookup = new DataComponentLookup<>(this.byId);
   }

   private synchronized void allcraftUndoReplace(
      ResourceKey<T> key,
      Holder.Reference<T> holder,
      T currentValue,
      T previousValue,
      int id,
      @Nullable RegistrationInfo previousInfo,
      Lifecycle previousLifecycle,
      boolean wasRetired
   ) {
      this.byValue.remove(currentValue, holder);
      this.toId.removeInt(currentValue);
      holder.bindValue(previousValue);
      this.byValue.put(previousValue, holder);
      this.toId.put(previousValue, id);
      if (previousInfo == null) {
         this.registrationInfos.remove(key);
      } else {
         this.registrationInfos.put(key, previousInfo);
      }
      if (wasRetired) {
         this.allcraftRetired.add(key);
      } else {
         this.allcraftRetired.remove(key);
      }
      this.registryLifecycle = previousLifecycle;
      this.componentLookup = new DataComponentLookup<>(this.byId);
   }

   private synchronized void allcraftUndoRemove(
      ResourceKey<T> key,
      Holder.Reference<T> holder,
      T value,
      int id,
      @Nullable RegistrationInfo info,
      Lifecycle previousLifecycle,
      boolean wasRetired
   ) {
      while (this.byId.size() <= id) {
         this.byId.add(null);
      }
      if (this.byId.get(id) != null) {
         throw new IllegalStateException("Cannot restore " + key + ": ID " + id + " was reused");
      }
      this.byId.set(id, holder);
      this.byKey.put(key, holder);
      this.byLocation.put(key.identifier(), holder);
      this.byValue.put(value, holder);
      this.toId.put(value, id);
      if (info != null) {
         this.registrationInfos.put(key, info);
      }
      if (wasRetired) {
         this.allcraftRetired.add(key);
      }
      this.registryLifecycle = previousLifecycle;
      this.componentLookup = new DataComponentLookup<>(this.byId);
   }

   @Override
   public @Nullable Identifier getKey(final T thing) {
      Holder.Reference<T> holder = this.byValue.get(thing);
      return holder != null ? holder.key().identifier() : null;
   }

   @Override
   public Optional<ResourceKey<T>> getResourceKey(final T thing) {
      return Optional.ofNullable(this.byValue.get(thing)).map(Holder.Reference::key);
   }

   @Override
   public int getId(final @Nullable T thing) {
      return this.toId.getInt(thing);
   }

   @Override
   public @Nullable T getValue(final @Nullable ResourceKey<T> key) {
      return getValueFromNullable(this.byKey.get(key));
   }

   @Override
   public @Nullable T byId(final int id) {
      Holder.Reference<T> holder = id >= 0 && id < this.byId.size() ? this.byId.get(id) : null;
      return holder == null ? null : holder.value();
   }

   @Override
   public Optional<Holder.Reference<T>> get(final int id) {
      return id >= 0 && id < this.byId.size() ? Optional.ofNullable((Holder.Reference<T>)this.byId.get(id)) : Optional.empty();
   }

   @Override
   public Optional<Holder.Reference<T>> get(final Identifier id) {
      return Optional.ofNullable(this.byLocation.get(id));
   }

   @Override
   public Optional<Holder.Reference<T>> get(final ResourceKey<T> id) {
      return Optional.ofNullable(this.byKey.get(id));
   }

   @Override
   public Optional<Holder.Reference<T>> getAny() {
      return this.byId.stream().filter(Objects::nonNull).findFirst();
   }

   @Override
   public Holder<T> wrapAsHolder(final T value) {
      Holder.Reference<T> existingHolder = this.byValue.get(value);
      return existingHolder != null ? existingHolder : Holder.direct(value);
   }

   private Holder.Reference<T> getOrCreateHolderOrThrow(final ResourceKey<T> key) {
      return this.byKey.computeIfAbsent(key, id -> {
         if (this.unregisteredIntrusiveHolders != null) {
            throw new IllegalStateException("This registry can't create new holders without value");
         }

         this.validateWrite((ResourceKey<T>)id);
         return Holder.Reference.createStandAlone(this, (ResourceKey<T>)id);
      });
   }

   @Override
   public int size() {
      return this.byKey.size();
   }

   @Override
   public Optional<RegistrationInfo> registrationInfo(final ResourceKey<T> element) {
      return Optional.ofNullable(this.registrationInfos.get(element));
   }

   @Override
   public Lifecycle registryLifecycle() {
      return this.registryLifecycle;
   }

   @Override
   public Iterator<T> iterator() {
      return Iterators.transform(Iterators.filter(this.byId.iterator(), Objects::nonNull), Holder::value);
   }

   @Override
   public @Nullable T getValue(final @Nullable Identifier key) {
      Holder.Reference<T> result = this.byLocation.get(key);
      return getValueFromNullable(result);
   }

   private static <T> @Nullable T getValueFromNullable(final Holder.@Nullable Reference<T> result) {
      return result != null ? result.value() : null;
   }

   @Override
   public Set<Identifier> keySet() {
      return Collections.unmodifiableSet(this.byLocation.keySet());
   }

   @Override
   public Set<ResourceKey<T>> registryKeySet() {
      return Collections.unmodifiableSet(this.byKey.keySet());
   }

   @Override
   public Set<Entry<ResourceKey<T>, T>> entrySet() {
      return Collections.unmodifiableSet(Util.<ResourceKey<T>, Holder.Reference<T>, T>mapValuesLazy(this.byKey, Holder::value).entrySet());
   }

   @Override
   public Stream<Holder.Reference<T>> listElements() {
      return this.byId.stream().filter(Objects::nonNull);
   }

   @Override
   public Stream<HolderSet.Named<T>> getTags() {
      return this.allTags.getTags();
   }

   private HolderSet.Named<T> getOrCreateTagForRegistration(final TagKey<T> tag) {
      return this.frozenTags.computeIfAbsent(tag, this::createTag);
   }

   private HolderSet.Named<T> createTag(final TagKey<T> tag) {
      return new HolderSet.Named<>(this, tag);
   }

   @Override
   public boolean isEmpty() {
      return this.byKey.isEmpty();
   }

   @Override
   public Optional<Holder.Reference<T>> getRandom(final RandomSource random) {
      return Util.getRandomSafe(this.byId.stream().filter(Objects::nonNull).toList(), random);
   }

   @Override
   public boolean containsKey(final Identifier key) {
      return this.byLocation.containsKey(key);
   }

   @Override
   public boolean containsKey(final ResourceKey<T> key) {
      return this.byKey.containsKey(key);
   }

   @Override
   public DataComponentLookup<T> componentLookup() {
      return Objects.requireNonNull(this.componentLookup, "Registry not frozen yet");
   }

   @Override
   public Registry<T> freeze() {
      if (this.frozen) {
         return this;
      }

      this.frozen = true;
      this.byValue.forEach((value, holder) -> holder.bindValue((T)value));
      List<Identifier> unboundEntries = this.byKey.entrySet().stream().filter(e -> !e.getValue().isBound()).map(e -> e.getKey().identifier()).sorted().toList();
      if (!unboundEntries.isEmpty()) {
         throw new IllegalStateException("Unbound values in registry " + this.key() + ": " + unboundEntries);
      }

      if (this.unregisteredIntrusiveHolders != null) {
         if (!this.unregisteredIntrusiveHolders.isEmpty()) {
            throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
         }

         this.unregisteredIntrusiveHolders.clear();
      }

      if (this.allTags.isBound()) {
         throw new IllegalStateException("Tags already present before freezing");
      }

      List<Identifier> unboundTags = this.frozenTags.entrySet().stream().filter(e -> !e.getValue().isBound()).map(e -> e.getKey().location()).sorted().toList();
      if (!unboundTags.isEmpty()) {
         throw new IllegalStateException("Unbound tags in registry " + this.key() + ": " + unboundTags);
      }

      this.componentLookup = new DataComponentLookup<>(this.byId);
      this.allTags = MappedRegistry.TagSet.fromMap(this.frozenTags);
      this.refreshTagsInHolders();
      return this;
   }

   @Override
   public Holder.Reference<T> createIntrusiveHolder(final T value) {
      if (!this.intrusiveHolders) {
         throw new IllegalStateException("This registry can't create intrusive holders");
      }

      this.validateWrite();
      if (this.unregisteredIntrusiveHolders == null) {
         this.unregisteredIntrusiveHolders = new IdentityHashMap<>();
      }
      return this.unregisteredIntrusiveHolders.computeIfAbsent(value, v -> Holder.Reference.createIntrusive(this, (T)v));
   }

   @Override
   public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
      return this.allTags.get(id);
   }

   private Holder.Reference<T> validateAndUnwrapTagElement(final TagKey<T> id, final Holder<T> value) {
      if (!value.canSerializeIn(this)) {
         throw new IllegalStateException("Can't create named set " + id + " containing value " + value + " from outside registry " + this);
      } else if (value instanceof Holder.Reference<T> reference) {
         return reference;
      } else {
         throw new IllegalStateException("Found direct holder " + value + " value in tag " + id);
      }
   }

   @Override
   public void bindTags(final Map<TagKey<T>, List<Holder<T>>> pendingTags) {
      this.validateWrite();
      pendingTags.forEach((id, values) -> this.getOrCreateTagForRegistration((TagKey<T>)id).bind((List<Holder<T>>)values));
   }

   private void refreshTagsInHolders() {
      Map<Holder.Reference<T>, List<TagKey<T>>> tagsForElement = new IdentityHashMap<>();
      this.byKey.values().forEach(h -> tagsForElement.put((Holder.Reference<T>)h, new ArrayList<>()));
      this.allTags.forEach((id, values) -> {
         for (Holder<T> value : values) {
            Holder.Reference<T> reference = this.validateAndUnwrapTagElement((TagKey<T>)id, value);
            tagsForElement.get(reference).add((TagKey<T>)id);
         }
      });
      tagsForElement.forEach(Holder.Reference::bindTags);
   }

   public void bindAllTagsToEmpty() {
      this.validateWrite();
      this.frozenTags.values().forEach(e -> e.bind(List.of()));
   }

   @Override
   public HolderGetter<T> createRegistrationLookup() {
      this.validateWrite();
      return new HolderGetter<T>() {
         @Override
         public Optional<Holder.Reference<T>> get(final ResourceKey<T> id) {
            return Optional.of(this.getOrThrow(id));
         }

         @Override
         public Holder.Reference<T> getOrThrow(final ResourceKey<T> id) {
            return MappedRegistry.this.getOrCreateHolderOrThrow(id);
         }

         @Override
         public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
            return Optional.of(this.getOrThrow(id));
         }

         @Override
         public HolderSet.Named<T> getOrThrow(final TagKey<T> id) {
            return MappedRegistry.this.getOrCreateTagForRegistration(id);
         }
      };
   }

   @Override
   public Registry.PendingTags<T> prepareTagReload(final TagLoader.LoadResult<T> tags) {
      if (!this.frozen) {
         throw new IllegalStateException("Invalid method used for tag loading");
      }

      Builder<TagKey<T>, HolderSet.Named<T>> pendingTagsBuilder = ImmutableMap.builder();
      final Map<TagKey<T>, List<Holder<T>>> pendingContents = new HashMap<>();
      tags.tags().forEach((id, contents) -> {
         HolderSet.Named<T> tagToAdd = this.frozenTags.get(id);
         if (tagToAdd == null) {
            tagToAdd = this.createTag((TagKey<T>)id);
         }

         pendingTagsBuilder.put(id, tagToAdd);
         pendingContents.put((TagKey<T>)id, List.copyOf(contents));
      });
      final ImmutableMap<TagKey<T>, HolderSet.Named<T>> pendingTags = pendingTagsBuilder.build();
      final HolderLookup.RegistryLookup<T> patchedHolder = new HolderLookup.RegistryLookup.Delegate<T>() {
         @Override
         public HolderLookup.RegistryLookup<T> parent() {
            return MappedRegistry.this;
         }

         @Override
         public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
            return Optional.ofNullable((HolderSet.Named<T>)pendingTags.get(id));
         }

         @Override
         public Stream<HolderSet.Named<T>> listTags() {
            return pendingTags.values().stream();
         }
      };
      return new Registry.PendingTags<T>() {
         @Override
         public ResourceKey<? extends Registry<? extends T>> key() {
            return MappedRegistry.this.key();
         }

         @Override
         public int size() {
            return pendingContents.size();
         }

         @Override
         public HolderLookup.RegistryLookup<T> lookup() {
            return patchedHolder;
         }

         @Override
         public void apply() {
            pendingTags.forEach((id, tag) -> {
               List<Holder<T>> values = pendingContents.getOrDefault(id, List.of());
               tag.bind(values);
            });
            MappedRegistry.this.allTags = MappedRegistry.TagSet.fromMap(pendingTags);
            MappedRegistry.this.refreshTagsInHolders();
         }
      };
   }

   private interface TagSet<T> {
      static <T> MappedRegistry.TagSet<T> unbound() {
         return new MappedRegistry.TagSet<T>() {
            @Override
            public boolean isBound() {
               return false;
            }

            @Override
            public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
               throw new IllegalStateException("Tags not bound, trying to access " + id);
            }

            @Override
            public void forEach(final BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action) {
               throw new IllegalStateException("Tags not bound");
            }

            @Override
            public Stream<HolderSet.Named<T>> getTags() {
               throw new IllegalStateException("Tags not bound");
            }
         };
      }

      static <T> MappedRegistry.TagSet<T> fromMap(final Map<TagKey<T>, HolderSet.Named<T>> tags) {
         return new MappedRegistry.TagSet<T>() {
            @Override
            public boolean isBound() {
               return true;
            }

            @Override
            public Optional<HolderSet.Named<T>> get(final TagKey<T> id) {
               return Optional.ofNullable(tags.get(id));
            }

            @Override
            public void forEach(final BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action) {
               tags.forEach(action);
            }

            @Override
            public Stream<HolderSet.Named<T>> getTags() {
               return tags.values().stream();
            }
         };
      }

      boolean isBound();

      Optional<HolderSet.Named<T>> get(TagKey<T> id);

      void forEach(BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> action);

      Stream<HolderSet.Named<T>> getTags();
   }
}
