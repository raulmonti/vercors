// -*- tab-width:2 ; indent-tabs-mode:nil -*-
//:: cases HistogramMatrix
//:: suite puptol
//:: tools silicon
//:: verdict Pass

//begin(all)
/*@
  given seq<seq<int> > data;
  invariant M>0 ** N > 0 ** P > 0 ** \matrix(matrix,M,N) ** \array(hist,P);
	invariant |data| == M;
	invariant (\forall int d; 0 <= d && d < M; |data[d]| == N);
  context (\forall* int i1=0 .. M ; (\forall* int j1=0 .. N ; Perm(matrix[i1][j1],1/2)));
  context (\forall int i1=0 .. M ; (\forall int j1=0 .. N ; matrix[i1][j1] == data[i1][j1] ));
  context (\forall int i1=0 .. M ; (\forall int j1=0 .. N ; 0 <= matrix[i1][j1] < P));
  context (\forall* int i1 = 0 .. P ; Perm(hist[i1],write));            
  ensures (\forall int k=0 .. P ; hist[k]==(\sum int i1 ; 0 <= i1 && i1 < M ;
              (\sum int j1 ; 0 <= j1 && j1 < N ; data[i1][j1]==k?1:0)));
  ensures (\forall int i1=0 .. M ; (\forall int j1=0 .. N ;  //skip(all)
              matrix[i1][j1]==\old(matrix[i1][j1])));        //skip(all)
@*/ void histogram(int M,int N,int matrix[M][N],int P,int hist[P]){
  for(int k=0;k<P;k++)/*@ context Perm(hist[k],write); ensures hist[k]==0; @*/ { hist[k]=0; }
  for(int i=0;i<M;i++){
    for(int j=0;j<N;j++) /*@
      requires (\forall* int k=0 .. P ; Reducible(hist[k],+)); $\label{histogram reducible}$
      context Perm(matrix[i][j],1/4) ** 0 <= matrix[i][j] < P ;
      context matrix[i][j] == data[i][j];
      ensures (\forall* int k=0 .. P ; Contribution(hist[k],data[i][j]==k?1:0));
    @*/ { hist[matrix[i][j]]+=1; } } }
//end(all)

