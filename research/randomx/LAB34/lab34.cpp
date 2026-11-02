#include <array>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>
using U=uint64_t;
struct State {std::array<U,8> r{};};
enum Op {IADD_RS, ISUB_R, IXOR_R, IMUL_R};
struct Ins {Op op; unsigned dst,src; U imm; unsigned shift;};
static void run(State& s,const std::vector<Ins>& p){
 for(auto x:p){U source=s.r[x.src];switch(x.op){
 case IADD_RS:s.r[x.dst]+= (source<<x.shift)+x.imm;break;
 case ISUB_R:s.r[x.dst]-=source;break;
 case IXOR_R:s.r[x.dst]^=source;break;
 case IMUL_R:s.r[x.dst]*=source;break;
 }}
}
static std::vector<Ins> simplify(const std::vector<Ins>& p){
 std::vector<Ins> out;
 for(auto x:p){
  if(x.op==IXOR_R && x.dst==x.src){out.push_back(x);continue;}
  if(!out.empty()){
   auto y=out.back();
   if(x.op==IXOR_R && y.op==IXOR_R && x.dst==y.dst && x.src==y.src && x.dst!=x.src){out.pop_back();continue;}
  }
  out.push_back(x);
 }
 return out;
}
int main(){
 std::mt19937_64 rng(0x4e45584953525834ULL);
 constexpr int TESTS=100000;
 unsigned long long before=0,after=0;
 for(int t=0;t<TESTS;++t){
  State a;for(auto &v:a.r)v=rng();State b=a;
  std::vector<Ins> p;
  for(int j=0;j<40;++j){unsigned d=rng()%8,s=rng()%8;Op op=Op(rng()%4);Ins x{op,d,s,rng(),unsigned(rng()%4)};
   p.push_back(x);
   if(op==IXOR_R && d!=s && rng()%3==0)p.push_back(x);
  }
  auto q=simplify(p);before+=p.size();after+=q.size();run(a,p);run(b,q);
  if(a.r!=b.r){std::printf("FAIL case=%d\n",t);return 1;}
 }
 std::printf("PASS %d/%d; instructions %llu -> %llu; removed %llu (RandomX-like integer subset, not full RandomX)\n",TESTS,TESTS,before,after,before-after);
}