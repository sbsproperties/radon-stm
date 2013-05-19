package net.fwbrasil.radon.transaction

import net.fwbrasil.radon.ConcurrentTransactionException
import net.fwbrasil.radon.ref.Ref
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import net.fwbrasil.radon.RetryLimitTransactionException

trait TransactionContext extends PropagationContext {

    protected[fwbrasil] val transactionManager =
        new TransactionManager()(this)

    private[radon] val transactionClock = new time.TransactionClock

    val retryLimit = 3000
    val milisToWaitBeforeRetry = 1

    type Transaction = net.fwbrasil.radon.transaction.Transaction

    def transactional[A](f: => A): A =
        transactional(transactionManager.getActiveTransaction, required)(f)

    def transactional[A](propagation: net.fwbrasil.radon.transaction.Propagation)(f: => A): A =
        transactional(transactionManager.getActiveTransaction, propagation)(f)

    def transactional[A](pTransaction: net.fwbrasil.radon.transaction.Transaction)(f: => A): A =
        transactional(Option(pTransaction))(f)

    def transactional[A](pTransaction: Option[net.fwbrasil.radon.transaction.Transaction])(f: => A): A =
        transactional(pTransaction, required)(f)

    def transactional[A](pTransaction: net.fwbrasil.radon.transaction.Transaction, propagation: Propagation)(f: => A): A =
        transactional(Option(pTransaction), propagation)(f)

    def transactional[A](transaction: Option[net.fwbrasil.radon.transaction.Transaction], propagation: Propagation)(f: => A): A = {
        val activeTransaction = transactionManager.getActiveTransaction
        if (activeTransaction != None && activeTransaction != transaction)
            throw new IllegalStateException("There is another active transaction!")
        if (transaction.isDefined)
            propagation.execute(transaction)(f)(this)
        else
            propagation.execute(transaction)(f)(this)
    }

    class TransactionalExecutionContext(ectx: ExecutionContext) extends ExecutionContext {
        val transaction = new Transaction()(TransactionContext.this)
        override def execute(runnable: Runnable): Unit =
            ectx.execute {
                new Runnable {
                    override def run =
                        transactional(transaction) {
                            runnable.run
                        }
                }
            }
        override def reportFailure(t: Throwable): Unit =
            ectx.reportFailure(t)
    }

    def asyncTransactional[A](f: => A)(implicit ectx: ExecutionContext): Future[A] = {
        val transaction = new Transaction()(TransactionContext.this)
        Future(transactional(transaction)(f))
            .flatMap {
                result =>
                    transaction.asyncCommit.map(_ => result)
            }
    }

    def asyncTransactionalFuture[A](future: TransactionalExecutionContext => Future[A])(implicit ectx: ExecutionContext) = {
        val ctx = new TransactionalExecutionContext(ectx)
        future(ctx).flatMap {
            result =>
                ctx.transaction.asyncCommit.map(_ => result)
        }
    }

    def transactionalWhile[A](cond: => Boolean)(f: => A): Unit = {
        var continue: Boolean = true
        while (continue) transactional {
            continue = cond
            if (continue) {
                f
            }
        }
    }

    def retry(refs: Ref[_]*): Unit =
        retry(refs.toList)

    def retry(refs: List[Ref[_]]): Unit =
        throw new ConcurrentTransactionException(refs)

    def makeDurableAsync(transaction: Transaction)(implicit ectx: ExecutionContext): Future[Unit] =
        Future()

    def makeDurable(transaction: Transaction) = {}

}