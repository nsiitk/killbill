/*
 * Copyright 2010-2012 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.util.entity.dao;

import java.util.Iterator;
import java.util.UUID;

import com.ning.billing.BillingExceptionBase;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.callcontext.InternalTenantContext;
import com.ning.billing.util.audit.ChangeType;
import com.ning.billing.util.entity.DefaultPagination;
import com.ning.billing.util.entity.Entity;
import com.ning.billing.util.entity.Pagination;

public abstract class EntityDaoBase<M extends EntityModelDao<E>, E extends Entity, U extends BillingExceptionBase> implements EntityDao<M, E, U> {

    protected final EntitySqlDaoTransactionalJdbiWrapper transactionalSqlDao;

    private final Class<? extends EntitySqlDao<M, E>> realSqlDao;

    public EntityDaoBase(final EntitySqlDaoTransactionalJdbiWrapper transactionalSqlDao, final Class<? extends EntitySqlDao<M, E>> realSqlDao) {
        this.transactionalSqlDao = transactionalSqlDao;
        this.realSqlDao = realSqlDao;
    }

    @Override
    public void create(final M entity, final InternalCallContext context) throws U {
        transactionalSqlDao.execute(getCreateEntitySqlDaoTransactionWrapper(entity, context));
    }

    protected EntitySqlDaoTransactionWrapper<Void> getCreateEntitySqlDaoTransactionWrapper(final M entity, final InternalCallContext context) {
        return new EntitySqlDaoTransactionWrapper<Void>() {
            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);

                if (checkEntityAlreadyExists(transactional, entity, context)) {
                    throw generateAlreadyExistsException(entity, context);
                }
                transactional.create(entity, context);

                final M refreshedEntity = transactional.getById(entity.getId().toString(), context);

                postBusEventFromTransaction(entity, refreshedEntity, ChangeType.INSERT, entitySqlDaoWrapperFactory, context);
                return null;
            }
        };
    }

    protected boolean checkEntityAlreadyExists(final EntitySqlDao<M, E> transactional, final M entity, final InternalCallContext context) {
        return transactional.getById(entity.getId().toString(), context) != null;
    }

    protected void postBusEventFromTransaction(final M entity, final M savedEntity, final ChangeType changeType,
                                               final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory,
                                               final InternalCallContext context) throws BillingExceptionBase {
    }

    protected abstract U generateAlreadyExistsException(final M entity, final InternalCallContext context);

    protected String getNaturalOrderingColumns() {
        return "recordId";
    }

    @Override
    public Long getRecordId(final UUID id, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Long>() {

            @Override
            public Long inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);
                return transactional.getRecordId(id.toString(), context);
            }
        });
    }

    @Override
    public M getByRecordId(final Long recordId, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<M>() {

            @Override
            public M inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);
                return transactional.getByRecordId(recordId, context);
            }
        });
    }

    @Override
    public M getById(final UUID id, final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<M>() {

            @Override
            public M inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);
                return transactional.getById(id.toString(), context);
            }
        });
    }

    @Override
    public Pagination<M> getAll(final InternalTenantContext context) {
        // We usually always want to wrap our queries in an EntitySqlDaoTransactionWrapper... except here.
        // Since we want to stream the results out, we don't want to auto-commit when this method returns.
        final EntitySqlDao<M, E> sqlDao = transactionalSqlDao.onDemand(realSqlDao);

        // Note: we need to perform the count before streaming the results, as the connection
        // will be busy as we stream the results out. This is also why we cannot use
        // SQL_CALC_FOUND_ROWS / FOUND_ROWS (which may ne be faster anyways).
        final Long count = sqlDao.getCount(context);

        final Iterator<M> results = sqlDao.getAll(context);
        return new DefaultPagination<M>(count, results);
    }

    @Override
    public Pagination<M> get(final Long offset, final Long limit, final InternalTenantContext context) {
        // Note: the connection will be busy as we stream the results out: hence we cannot use
        // SQL_CALC_FOUND_ROWS / FOUND_ROWS on the actual query.
        // We still need to know the actual number of results, mainly for the UI so that it knows if it needs to fetch
        // more pages. To do that, we perform a dummy search query with SQL_CALC_FOUND_ROWS (but limit 1).
        final Long count = transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Long>() {
            @Override
            public Long inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> sqlDao = entitySqlDaoWrapperFactory.become(realSqlDao);
                final Iterator<M> dumbIterator = sqlDao.get(offset, 1L, getNaturalOrderingColumns(), context);
                // Make sure to go through the results to close the connection
                while (dumbIterator.hasNext()) {
                    dumbIterator.next();
                }
                return sqlDao.getFoundRows(context);
            }
        });

        // We usually always want to wrap our queries in an EntitySqlDaoTransactionWrapper... except here.
        // Since we want to stream the results out, we don't want to auto-commit when this method returns.
        final EntitySqlDao<M, E> sqlDao = transactionalSqlDao.onDemand(realSqlDao);
        final Long totalCount = sqlDao.getCount(context);
        final Iterator<M> results = sqlDao.get(offset, limit, getNaturalOrderingColumns(), context);

        return new DefaultPagination<M>(offset, limit, count, totalCount, results);
    }

    @Override
    public Long getCount(final InternalTenantContext context) {
        return transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Long>() {

            @Override
            public Long inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);
                return transactional.getCount(context);
            }
        });
    }

    @Override
    public void test(final InternalTenantContext context) {
        transactionalSqlDao.execute(new EntitySqlDaoTransactionWrapper<Void>() {

            @Override
            public Void inTransaction(final EntitySqlDaoWrapperFactory<EntitySqlDao> entitySqlDaoWrapperFactory) throws Exception {
                final EntitySqlDao<M, E> transactional = entitySqlDaoWrapperFactory.become(realSqlDao);
                transactional.test(context);
                return null;
            }
        });
    }
}
