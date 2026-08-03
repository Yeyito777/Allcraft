package net.minecraft.core;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import net.minecraft.allcraft.AllcraftRegistries;
import org.jspecify.annotations.Nullable;

public class IdMapper<T> implements IdMap<T> {
   private int nextId;
   private final Reference2IntMap<T> tToId;
   private final List<T> idToT;

   public IdMapper() {
      this(512);
   }

   public IdMapper(final int expectedSize) {
      this.idToT = Lists.newArrayListWithExpectedSize(expectedSize);
      this.tToId = new Reference2IntOpenHashMap(expectedSize);
      this.tToId.defaultReturnValue(-1);
   }

   public synchronized void addMapping(final T thing, final int id) {
      int previousThingId = this.tToId.getInt(thing);
      T previousAtId = id >= 0 && id < this.idToT.size() ? this.idToT.get(id) : null;
      int previousNextId = this.nextId;
      if (previousThingId == id && previousAtId == thing) {
         return;
      }
      if (previousAtId != null && previousAtId != thing) {
         throw new IllegalStateException("ID " + id + " is already occupied");
      }
      this.tToId.put(thing, id);

      while (this.idToT.size() <= id) {
         this.idToT.add(null);
      }

      this.idToT.set(id, thing);
      if (this.nextId <= id) {
         this.nextId = id + 1;
      }
      if (AllcraftRegistries.mutationAllowed()) {
         AllcraftRegistries.recordRetainedUndo("restore runtime ID mapping " + id, () -> {
            synchronized (IdMapper.this) {
               IdMapper.this.tToId.removeInt(thing);
               if (previousThingId >= 0) {
                  IdMapper.this.tToId.put(thing, previousThingId);
               }
               if (id < IdMapper.this.idToT.size() && IdMapper.this.idToT.get(id) == thing) {
                  IdMapper.this.idToT.set(id, previousAtId);
               }
               while (!IdMapper.this.idToT.isEmpty() && IdMapper.this.idToT.get(IdMapper.this.idToT.size() - 1) == null) {
                  IdMapper.this.idToT.remove(IdMapper.this.idToT.size() - 1);
               }
               IdMapper.this.nextId = previousNextId;
            }
         }, () -> {
            // Published block/fluid state IDs are process-lifetime identities and cannot be reused.
         });
      }
   }

   public void add(final T thing) {
      this.addMapping(thing, this.allcraftNextId());
   }

   public synchronized int allcraftNextId() {
      if (AllcraftRegistries.mutationAllowed()) {
         for (int id = 0; id < this.idToT.size(); id++) {
            if (this.idToT.get(id) == null) {
               return id;
            }
         }
      }
      return this.nextId;
   }

   @Override
   public int getId(final T thing) {
      return this.tToId.getInt(thing);
   }

   @Override
   public final @Nullable T byId(final int id) {
      return id >= 0 && id < this.idToT.size() ? this.idToT.get(id) : null;
   }

   @Override
   public Iterator<T> iterator() {
      return Iterators.filter(this.idToT.iterator(), Objects::nonNull);
   }

   public boolean contains(final int id) {
      return this.byId(id) != null;
   }

   @Override
   public int size() {
      return this.tToId.size();
   }
}
